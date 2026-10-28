package antikristianos.offlinefoodpreservation;

import java.lang.ref.WeakReference;
import java.util.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaEventManager;
import zombie.Lua.LuaManager;
import zombie.characters.Capability;
import zombie.characters.IsoPlayer;
import zombie.iso.BuildingDef;
import zombie.iso.IsoGridSquare;
import zombie.iso.RoomDef;
import zombie.iso.areas.SafeHouse;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataChunk;
import zombie.iso.areas.isoregion.regions.IsoWorldRegion;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.ServerMap;
import zombie.network.WarManager;

/** On-demand geometry only: native regions, existing SafeHouse object and native save format. */
public final class SafehouseExpansion {
    static final int MAX_SQUARES=65536, MAX_WORK=262144;
    static final Map<IsoPlayer,Long> requests=new WeakHashMap<>();
    private static final Map<SafeHouse,Bounds> serverOrigins=new WeakHashMap<>();
    // Only client aliases for delayed native sync packets; never a persistent ownership database.
    private static final Map<Bounds,WeakReference<SafeHouse>> aliases=new LinkedHashMap<>();
    record Bounds(int x,int y,int w,int h) {
        int x2(){return x+w;} int y2(){return y+h;}
        boolean contains(Bounds b){return x<=b.x&&y<=b.y&&x2()>=b.x2()&&y2()>=b.y2();}
        boolean overlaps(Bounds b){return x<b.x2()&&x2()>b.x&&y<b.y2()&&y2()>b.y;}
        long area(){return (long)w*h;}
    }
    static Bounds bounds(SafeHouse h){return new Bounds(h.getX(),h.getY(),h.getW(),h.getH());}
    static Integer integer(KahluaTable args,String key){
        Object raw=args==null?null:args.rawget(key);
        if(!(raw instanceof Number n))return null;
        double value=n.doubleValue();return Double.isFinite(value)&&value==Math.rint(value)&&value>=Integer.MIN_VALUE&&value<=Integer.MAX_VALUE?(int)value:null;
    }
    static Bounds read(KahluaTable args,String prefix){
        Integer x=integer(args,prefix+"X"),y=integer(args,prefix+"Y"),w=integer(args,prefix+"W"),h=integer(args,prefix+"H");
        if(x==null||y==null||w==null||h==null||w<=0||h<=0||Math.abs((long)x)>1000000||Math.abs((long)y)>1000000||w>MAX_SQUARES||h>MAX_SQUARES)return null;
        return new Bounds(x,y,w,h);
    }
    static void write(KahluaTable table,String prefix,Bounds b){
        table.rawset(prefix+"X",(double)b.x);table.rawset(prefix+"Y",(double)b.y);table.rawset(prefix+"W",(double)b.w);table.rawset(prefix+"H",(double)b.h);
    }
    private static KahluaTable result(String status,KahluaTable args){
        KahluaTable result=LuaManager.platform.newTable();result.rawset("status",status);
        Integer request=integer(args,"request");if(request!=null)result.rawset("request",request.doubleValue());return result;
    }
    static boolean eligible(IsoWorldRegion region){return region!=null&&region.isEnclosed()&&region.getRoofedPercentage()>=0.5f;}
    static Bounds fitAroundClaims(Bounds old,Bounds proposed,SafeHouse target){
        ArrayList<Bounds> candidates=new ArrayList<>();candidates.add(proposed);int work=0;
        for(SafeHouse other:SafeHouse.getSafehouseList()){
            if(other==target)continue;
            Bounds obstacle=bounds(other);ArrayList<Bounds> next=new ArrayList<>();
            for(Bounds candidate:candidates){
                if(++work>MAX_WORK)throw new Rejected("Limit");
                if(!candidate.overlaps(obstacle)){next.add(candidate);continue;}
                // Every valid rectangle must pass on at least one of the four obstacle sides.
                if(obstacle.x2()<=old.x)next.add(new Bounds(Math.max(candidate.x,obstacle.x2()),candidate.y,candidate.x2()-Math.max(candidate.x,obstacle.x2()),candidate.h));
                if(obstacle.x>=old.x2())next.add(new Bounds(candidate.x,candidate.y,Math.min(candidate.x2(),obstacle.x)-candidate.x,candidate.h));
                if(obstacle.y2()<=old.y)next.add(new Bounds(candidate.x,Math.max(candidate.y,obstacle.y2()),candidate.w,candidate.y2()-Math.max(candidate.y,obstacle.y2())));
                if(obstacle.y>=old.y2())next.add(new Bounds(candidate.x,candidate.y,candidate.w,Math.min(candidate.y2(),obstacle.y)-candidate.y));
            }
            candidates.clear();
            for(Bounds b:new LinkedHashSet<>(next)){
                if(!b.contains(old))continue;
                boolean covered=false;
                for(Bounds larger:next){if(++work>MAX_WORK)throw new Rejected("Limit");if(!b.equals(larger)&&larger.contains(b)){covered=true;break;}}
                if(!covered)candidates.add(b);
            }
            if(candidates.isEmpty())throw new Rejected("Overlap");
        }
        Bounds best=old;for(Bounds candidate:candidates)if(candidate.area()>best.area())best=candidate;
        return best;
    }
    private static final class Rejected extends RuntimeException {Rejected(String reason){super(reason);}}
    static RoomDef mapRoom(IsoGridSquare square){
        RoomDef room=square==null?null:square.getRoomDef();
        BuildingDef building=room==null?null:room.getBuilding();
        return building!=null&&!building.isUserDefined()&&!room.isEmptyOutside()&&room.isInside(square.x,square.y,square.z)?room:null;
    }
    /** Native map rooms are anchors even when IsoRegions has never indexed their chunk or roof. */
    private static final class Search {
        final Set<IsoWorldRegion> found=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<IsoWorldRegion,Boolean> eligibility=new IdentityHashMap<>();
        final ArrayDeque<IsoWorldRegion> queue=new ArrayDeque<>();
        int work;
        void step(){if(++work>MAX_WORK)throw new Rejected("Limit");}
        void offer(int x,int y,int z){
            if(z>=0&&z<32&&IsoRegions.getIsoWorldRegion(x,y,z) instanceof IsoWorldRegion region)offer(region);
        }
        void offer(IsoWorldRegion region){
            if(!found.contains(region)&&eligibility.computeIfAbsent(region,this::eligible)){found.add(region);queue.add(region);}
        }
        boolean eligible(IsoWorldRegion region){
            if(!region.isEnclosed())return false;
            if(SafehouseExpansion.eligible(region))return true;
            // Sloping/prefab roofs need not supply the solid floor above that recalcRoofs counts.
            // Supplement only actual map-room tiles, never an entire annex or an outdoor region.
            int roofed=region.getRoofCnt();
            for(var part:region.getChunkRegions()){
                var data=part.getDataChunk();int z=part.getzLayer(),cx=data.getChunkX(),cy=data.getChunkY();
                var chunk=ServerMap.instance.getChunk(cx,cy);if(chunk==null)throw new Rejected("Unloaded");
                for(int ly=0;ly<8;ly++)for(int lx=0;lx<8;lx++){
                    step();if(data.getIsoChunkRegion(lx,ly,z)!=part)continue;
                    if(mapRoom(chunk.getGridSquare(lx,ly,z))!=null&&!regionRoofed(data,lx,ly,z))roofed++;
                }
            }
            return roofed>=region.getSquareSize()*0.5f;
        }
        boolean regionRoofed(DataChunk data,int x,int y,int target){
            // Replay Vanilla recalcRoofs for this column to avoid counting a mapped tile twice.
            int highest=31;
            while(highest>0){step();if(data.getSquare(x,y,highest)>=0)break;highest--;}
            if(highest<=target)return false;
            boolean roof=(data.getSquare(x,y,highest)&16)!=0;
            for(int z=highest-1;z>=target;z--){
                step();int flags=data.getSquare(x,y,z);var part=data.getIsoChunkRegion(x,y,z);
                var region=part==null?null:part.getIsoWorldRegion();
                if(flags>0||(region!=null&&region.isEnclosed())){
                    roof|=flags>=0&&(flags&32)!=0;
                    if(roof){
                        if(z==target)return part!=null;
                        if(region==null||!region.isEnclosed())roof=false;
                    }else roof=flags>=0&&(flags&16)!=0;
                }else roof=false;
            }
            return false;
        }
    }
    /** Bounded request-local traversal; no world scan or work added to stable render/simulation frames. */
    static Bounds calculate(Bounds old){
        if(old.area()>MAX_SQUARES)throw new Rejected("Limit");
        Search search=new Search();Set<BuildingDef> prefabs=Collections.newSetFromMap(new IdentityHashMap<>());
        // The existing protected interior is the seed, never a client-supplied proposed footprint.
        for(int cy=Math.floorDiv(old.y+2,8);cy<=Math.floorDiv(old.y2()-3,8);cy++)for(int cx=Math.floorDiv(old.x+2,8);cx<=Math.floorDiv(old.x2()-3,8);cx++){
            var chunk=ServerMap.instance.getChunk(cx,cy);
            if(chunk==null)throw new Rejected("Unloaded");
            for(int z=Math.max(0,chunk.getMinLevel());z<=Math.min(31,chunk.getMaxLevel());z++)for(int ly=0;ly<8;ly++)for(int lx=0;lx<8;lx++){
                search.step();
                int x=cx*8+lx,y=cy*8+ly;if(x<old.x+2||x>=old.x2()-2||y<old.y+2||y>=old.y2()-2)continue;
                RoomDef room=mapRoom(chunk.getGridSquare(lx,ly,z));if(room!=null)prefabs.add(room.getBuilding());
                search.offer(x,y,z);
            }
        }
        // Static map rooms can bridge absent/unroofed raw regions to a closed, roofed annex.
        // Use exact room rectangles, excluding emptyoutside and bounding-box gaps/courtyards.
        for(BuildingDef building:prefabs)for(RoomDef room:building.getRooms()){
            if(room.isEmptyOutside()||room.level<0||room.level>=32)continue;
            for(var rect:room.getRects())for(int y=rect.y;y<rect.getY2();y++)for(int x=rect.x;x<rect.getX2();x++){
                search.step();if(ServerMap.instance.getChunk(Math.floorDiv(x,8),Math.floorDiv(y,8))==null)throw new Rejected("Unloaded");
                search.offer(x,y,room.level);search.offer(x-1,y,room.level);search.offer(x+1,y,room.level);
                search.offer(x,y-1,room.level);search.offer(x,y+1,room.level);
                search.offer(x,y,room.level-1);search.offer(x,y,room.level+1);
            }
        }
        if(search.queue.isEmpty()&&prefabs.isEmpty())throw new Rejected("NoStructure");
        int minX=old.x,minY=old.y,maxX=old.x2(),maxY=old.y2(),squares=0;
        while(!search.queue.isEmpty()){
            IsoWorldRegion region=search.queue.remove();
            // Same adjacency/enclosure/roof rule used by Vanilla's building grouping, including closed rooms.
            for(IsoWorldRegion neighbor:region.getNeighbors())search.offer(neighbor);
            for(var part:region.getChunkRegions()){
                var data=part.getDataChunk();int z=part.getzLayer(),cx=data.getChunkX(),cy=data.getChunkY();
                if(ServerMap.instance.getChunk(cx,cy)==null)throw new Rejected("Unloaded");
                for(int ly=0;ly<8;ly++)for(int lx=0;lx<8;lx++){
                    search.step();
                    if(data.getIsoChunkRegion(lx,ly,z)!=part)continue;
                    if(++squares>MAX_SQUARES)throw new Rejected("Limit");
                    int x=cx*8+lx,y=cy*8+ly;
                    minX=Math.min(minX,x-2);minY=Math.min(minY,y-2);maxX=Math.max(maxX,x+3);maxY=Math.max(maxY,y+3);
                    // Vertically overlapping interior regions join floors, including prefab extensions.
                    search.offer(x,y,z-1);search.offer(x,y,z+1);
                }
            }
        }
        Bounds next=new Bounds(minX,minY,maxX-minX,maxY-minY);
        if(next.area()>MAX_SQUARES)throw new Rejected("Limit");
        return next;
    }
    public static KahluaTable expand(IsoPlayer player,KahluaTable args){
        if(!GameServer.server||player==null||GameServer.IDToPlayerMap.get(player.getOnlineID())!=player)return result("Permission",args);
        Bounds old=read(args,"old");SafeHouse house=old==null?null:SafeHouse.getSafeHouse(old.x,old.y,old.w,old.h);
        if(house==null)return result("Missing",args);
        if(!house.getOwner().equals(player.getUsername())&&(player.getRole()==null||!player.getRole().hasCapability(Capability.CanSetupSafehouses)))return result("Permission",args);
        if(!house.containsLocation(player.getX(),player.getY()))return result("Outside",args);
        long now=System.nanoTime();Long previous=requests.get(player);if(previous!=null&&now-previous<2_000_000_000L)return result("Busy",args);requests.put(player,now);
        // Vanilla's historically named isWarClaimed returns false while a war record exists.
        if(!WarManager.isWarClaimed(house.getOnlineID()))return result("War",args);
        try{
            Bounds next=fitAroundClaims(old,calculate(old),house);
            if(!next.contains(old)||next.area()<=old.area())return result("NoGrowth",args);
            for(SafeHouse other:SafeHouse.getSafehouseList())if(other!=house&&(next.overlaps(bounds(other))||other.getOnlineID()==SafeHouse.getOnlineID(next.x,next.y)))return result("Overlap",args);
            apply(house,next);
            FoodAuthority.changedHouses();
            Bounds origin=serverOrigins.computeIfAbsent(house,key->old);
            KahluaTable result=result("Success",args);write(result,"origin",origin);write(result,"old",old);write(result,"new",next);return result;
        }catch(Rejected e){return result(e.getMessage(),args);}
    }
    static void apply(SafeHouse house,Bounds b){
        // Avoid four intermediate geometries reaching the installed preservation setter hooks.
        Access.set(house,"x",b.x);Access.set(house,"y",b.y);Access.set(house,"w",b.w);Access.set(house,"h",b.h);
        house.setOnlineID(SafeHouse.getOnlineID(b.x,b.y));
    }
    public static void receive(KahluaTable args){
        if(!GameClient.client||GameServer.server)return;
        Bounds old=read(args,"old"),next=read(args,"new");if(old==null||next==null||!next.contains(old)||next.area()<=old.area())return;
        SafeHouse house=SafeHouse.getSafeHouse(old.x,old.y,old.w,old.h);
        SafeHouse duplicate=SafeHouse.getSafeHouse(next.x,next.y,next.w,next.h);
        if(house==null)house=duplicate;
        Bounds origin=read(args,"origin");
        if(house==null&&origin!=null)house=SafeHouse.getSafeHouse(origin.x,origin.y,origin.w,origin.h);
        if(house==null){WeakReference<SafeHouse> reference=aliases.get(old);house=reference==null?null:reference.get();}
        if(house==null||!SafeHouse.getSafehouseList().contains(house))return;
        if(!next.contains(bounds(house)))return; // A delayed broadcast cannot shrink a later expansion.
        if(duplicate!=null&&duplicate!=house)SafeHouse.removeSafeHouse(duplicate);
        aliases.put(old,new WeakReference<>(house));
        if(origin!=null)aliases.put(origin,new WeakReference<>(house));
        while(aliases.size()>256)aliases.remove(aliases.keySet().iterator().next());
        apply(house,next);LuaEventManager.triggerEvent("OnSafehousesChanged");
    }
    /** Prevent a delayed pre-expansion Vanilla sync from creating a duplicate client claim. */
    public static void beforeNativeSync(Object packet){
        if(!GameClient.client)return;
        Bounds incoming=new Bounds((int)Access.get(packet,"x"),(int)Access.get(packet,"y"),(int)Access.get(packet,"w"),(int)Access.get(packet,"h"));
        WeakReference<SafeHouse> reference=aliases.get(incoming);SafeHouse house=reference==null?null:reference.get();
        if(house==null||!SafeHouse.getSafehouseList().contains(house))return;
        Bounds current=bounds(house);
        Access.set(packet,"x",current.x);Access.set(packet,"y",current.y);Access.set(packet,"w",current.w);Access.set(packet,"h",current.h);
    }
    public static void resetClient(){aliases.clear();}
}
