package antikristianos.offlinefoodpreservation;

import java.nio.ByteBuffer;
import me.zed_0xff.zombie_buddy.Patch;
import zombie.characters.IsoPlayer;
import zombie.inventory.*;
import zombie.inventory.types.Food;
import zombie.core.network.*;
import zombie.core.raknet.UdpConnection;
import zombie.network.*;
import zombie.vehicles.BaseVehicle;

public final class Patches {
    @Patch(className="zombie.network.packets.safehouse.SafehouseSyncPacket",methodName="processClient",warmUp=true,strictMatch=true)
    public static final class ExpandedHouseSync {
        @Patch.OnEnter public static void enter(@Patch.This Object packet,@Patch.Argument(0) UdpConnection connection){SafehouseExpansion.beforeNativeSync(packet);}
    }
    @Patch(className="zombie.iso.areas.isoregion.IsoRegions",methodName="update",warmUp=true,strictMatch=true)
    public static final class PlayerBuildings {@Patch.OnExit public static void exit(){SelfBuiltSafehouses.update();}}
    @Patch(className="zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid",methodName="updateSquares",warmUp=true,strictMatch=true)
    public static final class PlayerBuildingSquares {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid converter){return SelfBuiltSafehouses.updateServerSquares(converter);}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="setCondition",warmUp=true,strictMatch=true)
    public static final class Condition {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This InventoryItem item,@Patch.Argument(0) int value,@Patch.Argument(1) boolean sound){return ConditionProtection.reject(item,value);}
    }
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="update",warmUp=true,strictMatch=true)
    public static final class Generator {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This zombie.iso.objects.IsoGenerator generator){return GeneratorProtection.beforeUpdate(generator);}
    }
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="addToWorld",warmUp=true,strictMatch=true)
    public static final class GeneratorAdded {@Patch.OnExit public static void exit(@Patch.This zombie.iso.objects.IsoGenerator generator){GeneratorProtection.added(generator);}}
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="removeFromWorld",warmUp=true,strictMatch=true)
    public static final class GeneratorUnloaded {@Patch.OnEnter public static void enter(@Patch.This zombie.iso.objects.IsoGenerator generator){GeneratorProtection.unloaded(generator);}}
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="remove",warmUp=true,strictMatch=true)
    public static final class GeneratorRemoved {@Patch.OnEnter public static void enter(@Patch.This zombie.iso.objects.IsoGenerator generator){GeneratorProtection.removed(generator);}}
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="save",warmUp=true,strictMatch=true)
    public static final class GeneratorSave {@Patch.OnEnter public static void enter(@Patch.This zombie.iso.objects.IsoGenerator generator,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) boolean net){GeneratorProtection.saving(generator);}}
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="setActivated",warmUp=true,strictMatch=true)
    public static final class GeneratorActivation {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This zombie.iso.objects.IsoGenerator generator,@Patch.Argument(0) boolean active){return GeneratorProtection.beforeActivation(generator,active);}}
    @Patch(className="zombie.iso.objects.IsoGenerator",methodName="syncIsoObjectReceive",warmUp=true,strictMatch=true)
    public static final class GeneratorReceive {@Patch.OnExit public static void exit(@Patch.This zombie.iso.objects.IsoGenerator generator,@Patch.Argument(0) ByteBufferReader buffer){GeneratorProtection.received(generator);}}
    @Patch(className="zombie.inventory.types.Food",methodName="updateAge",warmUp=true,strictMatch=true)
    public static final class Age {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This Food food,@Patch.Argument(value=0,readOnly=false) boolean send,@Patch.Local("skip") boolean skip){if(FoodAuthority.server())send=false;skip=FoodAuthority.beforeAge(food);return skip;}
        @Patch.OnExit public static void exit(@Patch.This Food food,@Patch.Argument(0) boolean send,@Patch.Local("skip") boolean skip){FoodAuthority.afterAge(food,skip);}
    }
    @Patch(className="zombie.inventory.types.Food",methodName="updateAge",warmUp=true,strictMatch=true)
    public static final class ClientAge {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This Food food){if(!FoodAuthority.client())return false;Access.freezingOnly(food,FoodAuthority.now());FoodAuthority.clientCalls++;return true;}
    }
    @Patch(className="zombie.inventory.types.Food",methodName="setAutoAge",warmUp=true,strictMatch=true)
    public static final class ClientAutoAge {
        @Patch.OnEnter(skipOn=true) public static boolean enter(){return FoodAuthority.client();}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="setAge",warmUp=true,strictMatch=true)
    public static final class ClientSetAge {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This InventoryItem item,@Patch.Argument(0) float value){return item instanceof Food&&FoodAuthority.rejectClientAge();}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="setLastAged",warmUp=true,strictMatch=true)
    public static final class ClientSetLast {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This InventoryItem item,@Patch.Argument(0) float value){return item instanceof Food&&FoodAuthority.rejectClientAge();}
    }
    @Patch(className="zombie.inventory.types.Food",methodName="save",warmUp=true,strictMatch=true)
    public static final class FoodSave {
        @Patch.OnEnter public static void enter(@Patch.This Food food,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) boolean net){FoodAuthority.fullSave(food);}
    }
    @Patch(className="zombie.inventory.types.Food",methodName="load",warmUp=true,strictMatch=true)
    public static final class FoodLoad {
        @Patch.OnExit public static void exit(@Patch.This Food food,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version){FoodAuthority.fullLoad(food);}
    }
    @Patch(className="zombie.characters.IsoPlayer",methodName="load",warmUp=true,strictMatch=true)
    public static final class PlayerLoad {
        @Patch.OnEnter public static void enter(@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version,@Patch.Argument(2) boolean net){FoodAuthority.loadEnter();}
        @Patch.OnExit(onThrowable=Throwable.class) public static void exit(@Patch.This IsoPlayer player,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version,@Patch.Argument(2) boolean net,@Patch.Thrown Throwable error){FoodAuthority.loadExit();if(error==null)FoodAuthority.playerLoaded(player);}
    }
    @Patch(className="zombie.characters.IsoPlayer",methodName="save",warmUp=true,strictMatch=true)
    public static final class PlayerSave {
        @Patch.OnEnter public static void enter(@Patch.This IsoPlayer player,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) boolean net){FoodAuthority.playerSaving(player);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="load",warmUp=true,strictMatch=true)
    public static final class ContainerLoad {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer c,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version){ClientItems.beginLoad(c);}
        @Patch.OnExit(onThrowable=Throwable.class) public static void exit(@Patch.This ItemContainer c,@Patch.Argument(0) ByteBuffer buffer,@Patch.Argument(1) int version,@Patch.Thrown Throwable error){try{if(error==null)FoodAuthority.containerLoaded(c);}finally{ClientItems.endLoad();}}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="setContainer",warmUp=true,strictMatch=true)
    public static final class SetContainer {
        @Patch.OnEnter public static void enter(@Patch.This InventoryItem item,@Patch.Argument(0) ItemContainer target,@Patch.Local("settled") boolean settled){settled=FoodAuthority.beforeMove(item,target);}
        @Patch.OnExit public static void exit(@Patch.This InventoryItem item,@Patch.Argument(0) ItemContainer target,@Patch.Local("settled") boolean settled){FoodAuthority.afterMove(item,settled);}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="OnBeforeRemoveFromContainer",warmUp=true,strictMatch=true)
    public static final class BeforeRemove {
        @Patch.OnEnter public static void enter(@Patch.This InventoryItem item,@Patch.Argument(0) ItemContainer source){FoodAuthority.beforeMove(item,null);ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="DoRemoveItem",warmUp=true,strictMatch=true)
    public static final class DirectRemove {
        @Patch.OnEnter public static void enter(@Patch.Argument(0) InventoryItem item){FoodAuthority.beforeMove(item,null);ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="Remove",warmUp=true,strictMatch=true)
    public static final class RemoveItem {
        @Patch.OnExit public static void exit(@Patch.Argument(0) InventoryItem item){if(item!=null&&item.getContainer()==null)ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="Remove",warmUp=true,strictMatch=true)
    public static final class RemoveByName {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer c,@Patch.Argument(0) String name){
            if(FoodAuthority.client())for(InventoryItem item:c.getItems())if(name.equals(item.getType())){ClientItems.forgetItem(item);break;}
        }
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="Remove",warmUp=true,strictMatch=true)
    public static final class RemoveByType {
        @Patch.OnExit public static void exit(@Patch.Argument(0) zombie.scripting.objects.ItemType type,@Patch.Return InventoryItem item){ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="RemoveAll",warmUp=true,strictMatch=true)
    public static final class RemoveAllByType {
        @Patch.OnExit public static void exit(@Patch.Argument(0) String type,@Patch.Argument(1) int count,@Patch.Return java.util.ArrayList<InventoryItem> items){if(FoodAuthority.client()&&items!=null)for(InventoryItem item:items)ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="RemoveOneOf",warmUp=true,strictMatch=true)
    public static final class RemoveOne {
        @Patch.OnExit public static void exit(@Patch.Argument(0) String type,@Patch.Argument(1) boolean recursive,@Patch.Return InventoryItem item){if(item!=null&&item.getContainer()==null)ClientItems.forgetItem(item);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="clear",warmUp=true,strictMatch=true)
    public static final class ClearContainer {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer c){ClientItems.forgetContents(c);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="removeAllItems",warmUp=true,strictMatch=true)
    public static final class EmptyContainer {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer c){ClientItems.forgetContents(c);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="setItems",warmUp=true,strictMatch=true)
    public static final class ReplaceContents {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer c,@Patch.Argument(0) java.util.ArrayList<InventoryItem> items){ClientItems.forgetContents(c);}
    }
    @Patch(className="zombie.inventory.ItemContainer",methodName="AddItem",warmUp=true,strictMatch=true)
    public static final class AddItem {
        @Patch.OnEnter public static void enter(@Patch.This ItemContainer target,@Patch.Argument(0) InventoryItem item){if(item!=null)FoodAuthority.beforeMove(item,target);}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="OnAddedToContainer",warmUp=true,strictMatch=true)
    public static final class Added {
        @Patch.OnExit public static void exit(@Patch.This InventoryItem item,@Patch.Argument(0) ItemContainer target){FoodAuthority.afterMove(item,false);}
    }
    @Patch(className="zombie.inventory.InventoryItem",methodName="setWorldItem",warmUp=true,strictMatch=true)
    public static final class WorldItem {
        @Patch.OnEnter public static void enter(@Patch.This InventoryItem item,@Patch.Argument(0) zombie.iso.objects.IsoWorldInventoryObject world,@Patch.Local("settled") boolean settled){settled=FoodAuthority.beforeMove(item,null);ClientItems.forgetItem(item);}
        @Patch.OnExit public static void exit(@Patch.This InventoryItem item,@Patch.Argument(0) zombie.iso.objects.IsoWorldInventoryObject world,@Patch.Local("settled") boolean settled){FoodAuthority.afterMove(item,settled);}
    }
    @Patch(className="zombie.network.GameServer",methodName="receivePlayerConnect",warmUp=true,strictMatch=true)
    public static final class Connect {
        @Patch.OnExit public static void exit(@Patch.Argument(0) ByteBufferReader b,@Patch.Argument(1) IConnection c,@Patch.Argument(2) String username){FoodAuthority.playerConnected();}
    }
    @Patch(className="zombie.network.GameServer",methodName="disconnect",warmUp=true,strictMatch=true)
    public static final class Disconnect {
        @Patch.OnExit public static void exit(@Patch.Argument(0) UdpConnection c,@Patch.Argument(1) String reason){FoodAuthority.changedHouses();}
    }
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="updatePlayersConnected",warmUp=true,strictMatch=true)
    public static final class Occupancy {
        @Patch.OnExit public static void exit(@Patch.This zombie.iso.areas.SafeHouse house){FoodAuthority.observeHouse(house);}
    }
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="removeSafeHouse",warmUp=true)
    public static final class RemoveHouse {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="addSafeHouse",warmUp=true)
    public static final class AddHouse {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="addPlayer",warmUp=true)
    public static final class AddResident {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="removePlayer",warmUp=true)
    public static final class RemoveResident {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setOwner",warmUp=true)
    public static final class Owner {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setPlayers",warmUp=true)
    public static final class Residents {@Patch.OnExit public static void exit(){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setH",warmUp=true,strictMatch=true)
    public static final class HouseBoundsH {@Patch.OnExit public static void exit(@Patch.Argument(0) int value){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setW",warmUp=true,strictMatch=true)
    public static final class HouseBoundsW {@Patch.OnExit public static void exit(@Patch.Argument(0) int value){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setY",warmUp=true,strictMatch=true)
    public static final class HouseBoundsY {@Patch.OnExit public static void exit(@Patch.Argument(0) int value){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.iso.areas.SafeHouse",methodName="setX",warmUp=true,strictMatch=true)
    public static final class HouseBoundsX {@Patch.OnExit public static void exit(@Patch.Argument(0) int value){FoodAuthority.changedHouses();}}
    @Patch(className="zombie.vehicles.BaseVehicle",methodName="update",warmUp=true,strictMatch=true)
    public static final class Vehicle {@Patch.OnExit public static void exit(@Patch.This BaseVehicle vehicle){FoodAuthority.vehicleMoved(vehicle);}}
    @Patch(className="zombie.characters.IsoGameCharacter",methodName="Eat",warmUp=true)
    public static final class Eat {@Patch.OnEnter public static void enter(@Patch.Argument(0) InventoryItem item){if(FoodAuthority.server()&&item instanceof Food food)FoodAuthority.settle(food);}}
    @Patch(className="zombie.network.packets.ItemStatsPacket",methodName="setData",warmUp=true,strictMatch=true)
    public static final class StatsSet {
        @Patch.OnEnter public static void enter(@Patch.Argument(0) Object[] args){Wire.prepare(args);}
        @Patch.OnExit public static void exit(@Patch.This Object p,@Patch.Argument(0) Object[] args){Wire.setData(p,args);}
    }
    @Patch(className="zombie.network.packets.ItemStatsPacket",methodName="write",warmUp=true,strictMatch=true)
    public static final class StatsWrite {@Patch.OnExit public static void exit(@Patch.This Object p,@Patch.Argument(0) ByteBufferWriter b){Wire.write(p,b);}}
    @Patch(className="zombie.network.packets.ItemStatsPacket",methodName="parse",warmUp=true,strictMatch=true)
    public static final class StatsParse {@Patch.OnExit public static void exit(@Patch.This Object p,@Patch.Argument(0) ByteBufferReader b,@Patch.Argument(1) IConnection c){Wire.parse(p,b,c);}}
    @Patch(className="zombie.network.packets.ItemStatsPacket",methodName="processClient",warmUp=true,strictMatch=true)
    public static final class StatsClient {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This Object p,@Patch.Argument(0) UdpConnection c){return Wire.processClient(p);}}
    @Patch(className="zombie.network.packets.ItemStatsPacket",methodName="processServer",warmUp=true,strictMatch=true)
    public static final class StatsServer {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This Object p,@Patch.Argument(0) PacketTypes.PacketType type,@Patch.Argument(1) UdpConnection c){return Wire.processServer(p,c);}}
    @Patch(className="zombie.network.packets.connection.LoadPlayerProfilePacket",methodName="write",warmUp=true,strictMatch=true)
    public static final class ProfileWrite {
        @Patch.OnEnter public static void enter(@Patch.This Object p,@Patch.Argument(0) ByteBufferWriter b){Profiles.prepare(p);}
        @Patch.OnExit public static void exit(@Patch.Argument(0) ByteBufferWriter b){Profiles.write(b);}
    }
    @Patch(className="zombie.network.packets.connection.LoadPlayerProfilePacket",methodName="parse",warmUp=true,strictMatch=true)
    public static final class ProfileParse {@Patch.OnExit public static void exit(@Patch.Argument(0) ByteBufferReader b,@Patch.Argument(1) IConnection c){Profiles.parse(b,c);}}
    @Patch(className="zombie.savefile.ServerPlayerDB",methodName="serverUpdateNetworkCharacter",warmUp=true,strictMatch=true)
    public static final class ClientProfileSave {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.This zombie.savefile.ServerPlayerDB db,@Patch.Argument(0) ByteBuffer b,@Patch.Argument(1) UdpConnection connection){
            if(!FoodAuthority.activeServer()||connection==null||!b.hasRemaining())return false;
            int index=b.get(b.position());if(index<0||index>=connection.players.length)return true;
            IsoPlayer player=connection.players[index];if(player==null)return false;
            db.serverUpdateNetworkCharacter(player,index,connection);return true;
        }
    }
    @Patch(className="zombie.inventory.types.Food",methodName="copyAgeFrom",warmUp=true,strictMatch=true)
    public static final class ClientCopyAge {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.Argument(0) Food source){return FoodAuthority.client();}}
    @Patch(className="zombie.inventory.types.Food",methodName="inheritFoodAgeFrom",warmUp=true,strictMatch=true)
    public static final class ClientInheritAge {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.Argument(0) InventoryItem source){return FoodAuthority.client();}}
    @Patch(className="zombie.inventory.types.Food",methodName="inheritOlderFoodAge",warmUp=true,strictMatch=true)
    public static final class ClientOlderAge {@Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.Argument(0) InventoryItem source){return FoodAuthority.client();}}
    @Patch(className="zombie.Lua.LuaEventManager",methodName="triggerEvent",warmUp=true)
    public static final class ProfileEvents {
        @Patch.OnEnter(skipOn=true) public static boolean enter(@Patch.Argument(0) String event){
            return FoodAuthority.preparingProfile();
        }
    }
    @Patch(className="zombie.characters.SurvivorDesc",methodName="load",warmUp=true,strictMatch=true)
    public static final class ProfileDescriptor {
        @Patch.OnEnter public static void enter(@Patch.Argument(0) ByteBuffer b,@Patch.Argument(1) int version,@Patch.Argument(2) zombie.characters.IsoGameCharacter player,@Patch.Local("previous") zombie.characters.SurvivorDesc previous){
            previous=Profiles.descriptorBefore(b);
        }
        @Patch.OnExit(onThrowable=Throwable.class) public static void exit(@Patch.This zombie.characters.SurvivorDesc descriptor,@Patch.Argument(0) ByteBuffer b,@Patch.Argument(1) int version,@Patch.Argument(2) zombie.characters.IsoGameCharacter player,@Patch.Local("previous") zombie.characters.SurvivorDesc previous){
            Profiles.descriptorAfter(descriptor,previous);
        }
    }
}
