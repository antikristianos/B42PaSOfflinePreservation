-- Lua binds lifecycle, server commands and the native safehouse window. Policy remains server-owned.
OFP = OFP or {}
local function native()
    if not OFP.Native or OFP.Native.version() ~= "1.6" then
        error("PlayerAndSavehouseOfflinePreservation 1.6: matching Java component required; activate ZombieBuddy and restart.")
    end
    return OFP.Native
end
local api
local function pulse() api.pulse() end
local function save() api.save() end
if isServer() then
    Events.OnClientCommand.Add(function(module, command, player, args)
        if module ~= "OFP" or command ~= "ExpandSafehouse" then return end
        local result = native().expandSafehouse(player, args)
        if result.status == "Success" then
            sendServerCommand("OFP", "SafehouseBounds", result)
        end
        sendServerCommand(player, "OFP", "ExpandResult", result)
    end)
    Events.OnInitGlobalModData.Add(function()
        api = native()
        api.initialize(ModData.getOrCreate("OfflineFoodPreservation"))
        -- The Java pulse checks its deadline, then processes a bounded queue.
        Events.OnTickEvenPaused.Remove(pulse)
        Events.OnTickEvenPaused.Add(pulse)
        Events.OnSave.Remove(save)
        Events.OnSave.Add(save)
    end)
elseif isClient() then
    local pending = {}
    local nextRequest = 0
    local function showResult(window, status)
        require "ISUI/ISModalDialog"
        local modal = ISModalDialog:new(0, 0, 420, 160, getText("IGUI_OFP_Expand" .. status), false, nil, nil, window.player:getPlayerNum())
        modal:initialise()
        modal:addToUIManager()
        modal.moveWithMouse = true
    end
    Events.OnServerCommand.Add(function(module, command, args)
        if module ~= "OFP" or not args then return end
        if command == "SafehouseBounds" then
            native().receiveSafehouseBounds(args)
        elseif command == "ExpandResult" then
            local window = pending[args.request]
            if not window then return end
            pending[args.request] = nil
            window.ofpExpandRequest = nil
            if window:isVisible() then showResult(window, args.status or "Failed") end
        end
    end)
    local function requestExpansion(window)
        if window.ofpExpandRequest or not window.safehouse or not window.player then return end
        if not window:isOwner() and not window:hasPrivilegedAccessLevel() then return end
        nextRequest = nextRequest + 1
        window.ofpExpandRequest = nextRequest
        window.ofpExpandSent = getTimestampMs()
        pending[nextRequest] = window
        local house = window.safehouse
        sendClientCommand(window.player, "OFP", "ExpandSafehouse", {
            request = nextRequest, oldX = house:getX(), oldY = house:getY(), oldW = house:getW(), oldH = house:getH()
        })
    end
    local function addExpansionButton(window)
        require "ISUI/ISButton"
        local line1, line2 = getText("IGUI_OFP_ExpandLine1"), getText("IGUI_OFP_ExpandLine2")
        local textManager = getTextManager()
        local fontHeight = textManager:getFontHeight(UIFont.Small)
        local width = math.max(100, textManager:MeasureStringX(UIFont.Small, line1) + 20, textManager:MeasureStringX(UIFont.Small, line2) + 20)
        local height = fontHeight * 2 + 8
        local list = window.playerList
        local button = ISButton:new(list:getRight() - width, list.y - height - 10, width, height, "", window, requestExpansion)
        button:initialise()
        button:instantiate()
        button.borderColor = window.buttonBorderColor
        button.tooltip = getText("IGUI_OFP_ExpandTooltip")
        -- Cache text and metrics once; preserve Vanilla button input, background and feedback.
        local originalRender = button.render
        function button:render()
            originalRender(self)
            local shade = self.enable and 1 or 0.3
            self:drawTextCentre(line1, self.width / 2, 4, shade, shade, shade, 1, UIFont.Small)
            self:drawTextCentre(line2, self.width / 2, 4 + fontHeight, shade, shade, shade, 1, UIFont.Small)
        end
        window.ofpExpandButton = button
        window:addChild(button)
        -- Long translations must not cover the native Allowed Players/Refresh row.
        local minimumWidth = window.refreshPlayerList:getRight() + width + 32
        if window:getWidth() < minimumWidth then
            window:setWidth(minimumWidth)
            list:setWidth(minimumWidth - 22)
            window.removePlayer:setX(list:getRight() - window.removePlayer:getWidth())
            window.quitSafehouse:setX(list:getRight() - window.quitSafehouse:getWidth())
            window.no:setX(list:getRight() - window.no:getWidth())
        end
        button:setX(list:getRight() - width)
        button:setVisible(window:isOwner() or window:hasPrivilegedAccessLevel())
    end
    local function installWindowHighlight()
        require "ISUI/UserPanel/ISSafehouseUI"
        if not ISSafehouseUI.ofpWindowHighlightInstalled then
            ISSafehouseUI.ofpWindowHighlightInstalled = true
            local previousInitialise = ISSafehouseUI.initialise
            function ISSafehouseUI:initialise(...)
                local result = previousInitialise(self, ...)
                addExpansionButton(self)
                return result
            end
            local previousUpdateButtons = ISSafehouseUI.updateButtons
            function ISSafehouseUI:updateButtons(...)
                local result = previousUpdateButtons(self, ...)
                local button = self.ofpExpandButton
                if button then
                    button:setVisible(self:isOwner() or self:hasPrivilegedAccessLevel())
                    button.enable = not self.ofpExpandRequest
                    if self.ofpExpandRequest and getTimestampMs() - self.ofpExpandSent > 15000 then
                        pending[self.ofpExpandRequest] = nil
                        self.ofpExpandRequest = nil
                        showResult(self, "Timeout")
                    end
                end
                return result
            end
            local previousClose = ISSafehouseUI.close
            function ISSafehouseUI:close(...)
                if self.ofpExpandRequest then pending[self.ofpExpandRequest] = nil; self.ofpExpandRequest = nil end
                return previousClose(self, ...)
            end
            local previousPrerender = ISSafehouseUI.prerender
            function ISSafehouseUI:prerender(...)
                local result = previousPrerender(self, ...)
                if self:isVisible() and self.safehouse and self.player and addAreaHighlightForPlayer then
                    local house = self.safehouse
                    local x, y, x2, y2 = house:getX(), house:getY(), house:getX2(), house:getY2()
                    local bounds = self.ofpSafehouseBounds
                    if not bounds or bounds.house ~= house or bounds.x ~= x or bounds.y ~= y or bounds.x2 ~= x2 or bounds.y2 ~= y2 then
                        bounds = { house = house, x = x, y = y, x2 = x2, y2 = y2 }
                        self.ofpSafehouseBounds = bounds
                    end
                    -- Vanilla expires this highlight when the window stops submitting it.
                    addAreaHighlightForPlayer(self.player:getPlayerNum(), bounds.x, bounds.y, bounds.x2, bounds.y2, 0, 1, 0, 0, 0.25)
                end
                return result
            end
        end
    end
    -- The shared bootstrap can load before the client ISUI base classes.
    Events.OnGameTimeLoaded.Add(function()
        native()
        native().resetSafehouseClient()
        installWindowHighlight()
    end)
end
