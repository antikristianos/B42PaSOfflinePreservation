package antikristianos.offlinefoodpreservation;

import java.lang.ref.*;
import java.util.*;
import zombie.inventory.*;
import zombie.inventory.types.*;

/** One bounded client index, not a second unbounded ID table. Never scans on ticks. */
public final class ClientItems {
    static final int LIMIT=65536, CLEAN_BUDGET=64;
    static final long PENDING_NANOS=60_000_000_000L;
    static final LinkedHashMap<Integer,Entry> entries=new LinkedHashMap<>();
    static final LinkedHashMap<Integer,Long> pending=new LinkedHashMap<>();
    static final ThreadLocal<ArrayDeque<Map<Integer,FoodAuthority.Snapshot>>> loading=new ThreadLocal<>();
    static final ReferenceQueue<Food> collected=new ReferenceQueue<>();
    static final class Entry extends WeakReference<Food> {
        final int id;final FoodAuthority.Snapshot row;
        Entry(int id,Food food,FoodAuthority.Snapshot row){super(food,collected);this.id=id;this.row=row;}
    }
    static void reset(){entries.clear();pending.clear();loading.remove();while(collected.poll()!=null){}}
    static void clean(){clean(System.nanoTime());}
    static void clean(long now) {
        for(int n=0;n<CLEAN_BUDGET;n++){Entry e=(Entry)collected.poll();if(e==null)break;if(entries.remove(e.id,e))pending.remove(e.id);}
        var it=pending.entrySet().iterator();int budget=CLEAN_BUDGET;
        while(budget-->0&&it.hasNext()) {
            var p=it.next();if(now-p.getValue()<PENDING_NANOS)break;
            Entry e=entries.get(p.getKey());if(e!=null&&e.get()==null)entries.remove(p.getKey());it.remove();
        }
    }
    static FoodAuthority.Snapshot newer(FoodAuthority.Snapshot a,FoodAuthority.Snapshot b) {
        if(a!=null&&a.epoch()!=FoodAuthority.epoch)a=null;
        if(b!=null&&b.epoch()!=FoodAuthority.epoch)b=null;
        return a==null?b:b==null||a.revision()>=b.revision()?a:b;
    }
    static FoodAuthority.Snapshot objectState(Food food) {
        if(food!=null&&food.getModData().rawget(FoodAuthority.STAMP) instanceof se.krka.kahlua.vm.KahluaTable stamp) {
            var row=new FoodAuthority.Snapshot((long)ClockHistory.number(stamp.rawget("epoch"),0),(long)ClockHistory.number(stamp.rawget("revision"),0),food.getAge(),food.getLastAged());
            if(row.valid()&&row.epoch()==FoodAuthority.epoch)return row;
        }
        return null;
    }
    static void stamp(Food food,FoodAuthority.Snapshot row) {
        Object old=food.getModData().rawget(FoodAuthority.STAMP);
        var stamp=old instanceof se.krka.kahlua.vm.KahluaTable t?t:zombie.Lua.LuaManager.platform.newTable();
        stamp.rawset("epoch",(double)row.epoch());stamp.rawset("revision",(double)row.revision());food.getModData().rawset(FoodAuthority.STAMP,stamp);
    }
    static FoodAuthority.Snapshot latest(int id,Food food) {
        Entry e=entries.get(id);FoodAuthority.Snapshot row=newer(e==null?null:e.row,objectState(food));
        var stack=loading.get();if(stack!=null)for(var layer:stack)row=newer(row,layer.get(id));return row;
    }
    static void put(int id,Food food,FoodAuthority.Snapshot row) {
        entries.remove(id);entries.put(id,new Entry(id,food,row));
        pending.remove(id);if(food==null)pending.put(id,System.nanoTime());
        while(entries.size()>LIMIT){int old=entries.keySet().iterator().next();entries.remove(old);pending.remove(old);}
    }
    static void register(Food food) {
        clean();FoodAuthority.Snapshot row=latest(food.getID(),food);Entry e=entries.get(food.getID());
        if(e==null||e.get()!=food||e.row!=row)put(food.getID(),food,row);
    }
    static Food find(ItemContainer root,int id) {
        clean();Entry e=entries.get(id);Food f=e==null?null:e.get();
        return f!=null&&f.getID()==id&&FoodAuthority.root(f.getContainer())==FoodAuthority.root(root)?f:null;
    }
    static void receive(int id,FoodAuthority.Snapshot row,Food food) {
        if(!FoodAuthority.client()||!row.valid()||row.epoch()!=FoodAuthority.epoch)return;
        clean();row=newer(row,latest(id,food));put(id,food,row);
        if(food!=null)FoodAuthority.apply(food,row);
    }
    static void applyPending(Food food) {
        FoodAuthority.Snapshot row=latest(food.getID(),food);
        if(row!=null)FoodAuthority.apply(food,row);
    }
    static void forget(Food food) {
        Entry e=entries.get(food.getID());
        if(e!=null&&(e.get()==food||e.get()==null)) {
            if(e.row!=null)FoodAuthority.apply(food,newer(e.row,objectState(food)));
            entries.remove(e.id);pending.remove(e.id);
        }
    }
    public static void forgetItem(InventoryItem item) {
        if(!FoodAuthority.client()||item==null)return;
        clean();if(item instanceof Food food)forget(food);
        else if(item instanceof InventoryContainer bag)FoodAuthority.walk(bag.getInventory(),ClientItems::forget);
    }
    public static void beginLoad(ItemContainer container) {
        if(!FoodAuthority.client())return;
        clean();var saved=new HashMap<Integer,FoodAuthority.Snapshot>();
        FoodAuthority.walk(container,food->{var row=latest(food.getID(),food);if(row!=null)saved.put(food.getID(),row);forget(food);});
        var stack=loading.get();if(stack==null){stack=new ArrayDeque<>();loading.set(stack);}stack.push(saved);
    }
    public static void endLoad() {
        var stack=loading.get();if(stack!=null){stack.pop();if(stack.isEmpty())loading.remove();}
    }
    public static void forgetContents(ItemContainer container) {
        if(FoodAuthority.client()){clean();FoodAuthority.walk(container,ClientItems::forget);}
    }
    static void moved(Food food) {
        clean();applyPending(food);
        // Leaving a carried inventory drops its indexed data immediately. A later world packet
        // may establish a new, bounded observation; its existing OFP2 stamp retains the revision without a separate cache.
        if(FoodAuthority.owner(food)==null)forget(food);else register(food);
    }
}
