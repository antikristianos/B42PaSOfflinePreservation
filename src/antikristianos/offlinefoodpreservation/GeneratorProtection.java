package antikristianos.offlinefoodpreservation;

import java.util.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.IsoGridSquare;
import zombie.iso.objects.IsoGenerator;

/** Server-owned activation only. Fuel, condition and native accounting are never adjusted. */
public final class GeneratorProtection {
    static final String CONTROL="OFPGeneratorSwitch1";
    static final WeakHashMap<IsoGenerator,Location> locations=new WeakHashMap<>();
    static long revision;
    static int switching;
    public static long associations, evaluations, stops, starts;
    static final class Location {
        int x,y,z; Object bucket; List<HouseClocks.House> houses;
        long evaluated=-1; boolean active,connected;
    }
    static boolean vanilla(IsoGenerator generator) {
        if("PSR.PowerBank".equals(generator.getModData().rawget("generatorFullType")))return false;
        return switch(generator.getGeneratorItemType()) {
            case "Base.Generator","Base.Generator_Yellow","Base.Generator_Blue","Base.Generator_Old" -> true;
            default -> false;
        };
    }
    static List<HouseClocks.House> covered(IsoGenerator generator) {
        IsoGridSquare square=generator.getSquare();if(square==null)return List.of();
        int x=square.getX(),y=square.getY(),z=square.getZ();
        var bucket=FoodAuthority.houses.cells.get(HouseClocks.cell(x,y));
        Location cached=locations.get(generator);
        if(cached!=null&&cached.x==x&&cached.y==y&&cached.z==z&&cached.bucket==bucket)return cached.houses;
        Location next=new Location();next.x=x;next.y=y;next.z=z;next.bucket=bucket;
        ArrayList<HouseClocks.House> result=new ArrayList<>();
        if(bucket!=null)for(var house:bucket)if(house.contains(x,y))result.add(house);
        next.houses=result;locations.put(generator,next);associations++;return result;
    }
    static HouseClocks.House currentHouse(List<HouseClocks.House> candidates) {
        HouseClocks.House result=null;
        for(var house:candidates)if(house.present){if(result!=null)return null;result=house;}
        return result;
    }
    static boolean matches(KahluaTable record,HouseClocks.House house,Location location) {
        return record!=null&&house!=null&&house.key.equals(record.rawget("house"))&&
            ClockHistory.number(record.rawget("generation"),-1)==house.generation&&
            ClockHistory.number(record.rawget("x"),Double.NaN)==location.x&&
            ClockHistory.number(record.rawget("y"),Double.NaN)==location.y&&
            ClockHistory.number(record.rawget("z"),Double.NaN)==location.z;
    }
    static void remember(IsoGenerator generator,HouseClocks.House house,Location location,boolean resume,double now) {
        KahluaTable record=LuaManager.platform.newTable();
        record.rawset("house",house.key);record.rawset("generation",(double)house.generation);
        record.rawset("x",(double)location.x);record.rawset("y",(double)location.y);record.rawset("z",(double)location.z);
        record.rawset("resume",resume);record.rawset("at",now);
        generator.getModData().rawset(CONTROL,record);
    }
    static void activate(IsoGenerator generator,boolean active) {
        switching++;
        try{generator.setActivated(active);}finally{switching--;}
        if(active)starts++;else stops++;
    }
    public static boolean beforeUpdate(IsoGenerator generator) {
        if(!FoodAuthority.server())return false;
        if(!FoodAuthority.ready)return true;
        if(generator.getSquare()==null)return false;
        var clocks=FoodAuthority.houses;
        if(clocks.refreshNeeded||FoodAuthority.now()<clocks.lastTime)clocks.refresh(FoodAuthority.now());
        boolean first=!locations.containsKey(generator);
        var candidates=covered(generator);var location=locations.get(generator);
        if(location.evaluated==revision&&location.active==generator.isActivated()&&location.connected==generator.isConnected())return false;
        reconcile(generator,currentHouse(candidates),location,first);
        location.evaluated=revision;location.active=generator.isActivated();location.connected=generator.isConnected();
        return false;
    }
    static void reconcile(IsoGenerator generator,HouseClocks.House house,Location location,boolean first) {
        evaluations++;
        var data=generator.getModData();data.rawset("OFPGeneratorCredit1",null);
        if(!vanilla(generator)){data.rawset(CONTROL,null);return;}
        KahluaTable record=data.rawget(CONTROL) instanceof KahluaTable t?t:null;
        if(!matches(record,house,location)){record=null;data.rawset(CONTROL,null);}
        if(house==null)return;
        double now=FoodAuthority.now();
        boolean resume=record!=null&&Boolean.TRUE.equals(record.rawget("resume"));
        boolean offline=!Double.isNaN(house.clock.open);
        // Replay a missed logout on reload using native switching, not resource/time corrections.
        boolean missedStop=first&&record!=null&&generator.isActivated()&&
            house.clock.between(ClockHistory.number(record.rawget("at"),now),now)>0;
        if(generator.isActivated()&&(offline||missedStop)) {
            remember(generator,house,location,true,now);activate(generator,false);resume=true;
        }
        if(!offline&&resume) {
            if(!generator.isActivated()&&generator.isConnected()&&generator.getFuel()>0&&generator.getCondition()>0)activate(generator,true);
            resume=false;
        }
        remember(generator,house,location,resume,now);
    }
    public static void changedHouses() {
        revision++;
        if(!FoodAuthority.activeServer())return;
        // Only already observed loaded generators, never a chunk/container discovery scan.
        for(var generator:new ArrayList<>(locations.keySet()))if(generator!=null)beforeUpdate(generator);
    }
    public static void added(IsoGenerator generator){beforeUpdate(generator);}
    public static void unloaded(IsoGenerator generator){locations.remove(generator);}
    public static void removed(IsoGenerator generator){if(FoodAuthority.server())generator.getModData().rawset(CONTROL,null);locations.remove(generator);}
    public static void saving(IsoGenerator generator) {
        if(!FoodAuthority.activeServer()||generator.getSquare()==null)return;
        beforeUpdate(generator);
        if(generator.getModData().rawget(CONTROL) instanceof KahluaTable record)record.rawset("at",(double)FoodAuthority.now());
    }
    public static boolean beforeActivation(IsoGenerator generator,boolean active) {
        if(!FoodAuthority.activeServer()||switching>0)return false;
        if(active&&vanilla(generator)&&generator.getSquare()!=null) {
            var clocks=FoodAuthority.houses;double now=FoodAuthority.now();
            if(clocks.refreshNeeded||now<clocks.lastTime)clocks.refresh(now);
            var square=generator.getSquare();var bucket=clocks.cells.get(HouseClocks.cell(square.getX(),square.getY()));
            HouseClocks.House owner=null;
            // A start attempt must not register a first observation or alter saved restart entitlement.
            if(bucket!=null)for(var house:bucket)if(house.present&&house.contains(square.getX(),square.getY())) {
                if(owner!=null){owner=null;break;}owner=house;
            }
            if(owner!=null&&!Double.isNaN(owner.clock.open))return true;
        }
        generator.getModData().rawset(CONTROL,null);
        var location=locations.get(generator);if(location!=null)location.evaluated=-1;
        return false;
    }
    public static void received(IsoGenerator generator) {
        if(!FoodAuthority.activeServer())return;
        var location=locations.get(generator);if(location!=null)location.evaluated=-1;
        beforeUpdate(generator);
    }
}
