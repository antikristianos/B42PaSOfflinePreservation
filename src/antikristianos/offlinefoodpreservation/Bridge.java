package antikristianos.offlinefoodpreservation;

import me.zed_0xff.zombie_buddy.Exposer;
import se.krka.kahlua.vm.KahluaTable;

@Exposer.LuaClass(name="OFP.Native")
public final class Bridge {
    public static String version(){if(!FoodAuthority.supported)throw new IllegalStateException("OFP native baseline verification failed");return FoodAuthority.VERSION;}
    public static void initialize(KahluaTable data){FoodAuthority.initialize(data);}
    public static void pulse(){FoodAuthority.pulse();}
    public static void save(){if(FoodAuthority.server()){FoodAuthority.houses.lastTime=FoodAuthority.now();FoodAuthority.houses.persist();}}
    public static String metrics(){return FoodAuthority.metrics();}
    public static KahluaTable expandSafehouse(zombie.characters.IsoPlayer player,KahluaTable args){return SafehouseExpansion.expand(player,args);}
    public static void receiveSafehouseBounds(KahluaTable args){SafehouseExpansion.receive(args);}
    public static void resetSafehouseClient(){SafehouseExpansion.resetClient();}
}
