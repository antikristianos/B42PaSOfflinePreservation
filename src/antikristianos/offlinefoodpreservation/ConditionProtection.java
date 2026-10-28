package antikristianos.offlinefoodpreservation;

import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.iso.IsoGridSquare;
import zombie.network.GameServer;

/** A setter boundary, never a scan or a restore-after-damage pass. */
public final class ConditionProtection {
    public static long prevented;
    public static boolean reject(InventoryItem item,int requested) {
        if(!FoodAuthority.activeServer() || requested>=item.getCondition()
                || FoodAuthority.loading.get()>0 || FoodAuthority.preparingProfile())return false;
        // PSR owns wear as well as charge for every battery it marks (Lua truthiness).
        Object capacity=item.hasModData()?item.getModData().rawget("PSR_maxCapacity"):null;
        if(capacity!=null&&!Boolean.FALSE.equals(capacity))return false;
        ItemContainer root=FoodAuthority.root(item.getContainer());
        if(root==null)return false;
        boolean protect;
        if(root.getParent() instanceof IsoPlayer player) {
            protect=!player.isDead() && GameServer.IDToPlayerMap.get(player.getOnlineID())!=player;
        } else {
            IsoGridSquare square=FoodAuthority.storageSquare(root);
            if(square==null)return false;
            HouseClocks houses=FoodAuthority.houses;
            double now=FoodAuthority.now();
            if(houses.refreshNeeded||now<houses.lastTime)houses.refresh(now);
            protect=houses.protectedNow(square.getX(),square.getY());
        }
        if(protect)prevented++;
        return protect;
    }
}
