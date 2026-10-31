package antikristianos.offlinefoodpreservation;

import java.lang.ref.*;
import java.util.*;
import java.util.function.Consumer;
import se.krka.kahlua.vm.KahluaTable;
import zombie.GameTime;
import zombie.Lua.LuaManager;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.inventory.InventoryItem;
import zombie.inventory.ItemContainer;
import zombie.inventory.types.Food;
import zombie.inventory.types.InventoryContainer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.objects.IsoDeadBody;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.vehicles.BaseVehicle;

/** All policy and elapsed-time computation lives here on the server. */
public final class FoodAuthority {
    public static final String VERSION="1.6", KEY="OfflineFoodPreservation", STAMP="OFP2";
    public static final int PROTOCOL=3;
    public static final float AGE_STEP=1f/60f, PUBLISH_STEP=5f/60f;
    static final HouseClocks houses=new HouseClocks();
    static final WeakHashMap<Food,State> states=new WeakHashMap<>();
    static final Map<Integer,FoodRef> items=new HashMap<>();
    static final ReferenceQueue<Food> collected=new ReferenceQueue<>();
    static final LinkedHashMap<Integer,FoodRef> dirty=new LinkedHashMap<>();
    static final WeakHashMap<BaseVehicle,int[]> vehicles=new WeakHashMap<>();
    static final ThreadLocal<int[]> positionOverride=new ThreadLocal<>();
    static final ThreadLocal<Integer> loading=ThreadLocal.withInitial(()->0), forcing=ThreadLocal.withInitial(()->0), applying=ThreadLocal.withInitial(()->0);
    static final ThreadLocal<Integer> profile=ThreadLocal.withInitial(()->0);
    static long epoch=System.currentTimeMillis(), nextPulse;
    static boolean ready;
    public static boolean supported;
    static float lastPulseWorld=Float.NaN;
    public static long ageCalls, ageCalculations, protectedCalculations, clientCalls, packets, rows, extensionBytes;
    static final class FoodRef extends WeakReference<Food> {
        final int id;
        FoodRef(Food food){super(food,collected);id=food.getID();}
    }
    static final class State {
        long revision=1;
        float age,last,publishedAge=Float.NaN,publishedAt=Float.NEGATIVE_INFINITY;
        int x=Integer.MIN_VALUE,y=Integer.MIN_VALUE;
        WeakReference<ItemContainer> root;
        boolean knownLocation,transferred,partialPause,terminal;
        float beforePartial,partialFrom; int partialX,partialY;
        State(Food f){age=f.getAge();last=f.getLastAged();terminal=f.isRotten();}
    }
    public record Snapshot(long epoch,long revision,float age,float last) {
        boolean valid(){return epoch>0&&revision>0&&Float.isFinite(age)&&Float.isFinite(last)&&age>=0;}
    }
    public static boolean server(){return supported&&GameServer.server&&!GameClient.client;}
    public static boolean activeServer(){return server()&&ready;}
    public static boolean preparingProfile(){return server()&&profile.get()>0;}
    public static boolean client(){return supported&&GameClient.client;}
    public static float now(){return (float)GameTime.getInstance().getWorldAgeHours();}
    static State state(Food f){return states.computeIfAbsent(f,State::new);}
    static void register(Food f){if(profile.get()>0||personal(f))return;if(client()){ClientItems.register(f);return;}FoodRef ref=items.get(f.getID());if(ref==null||ref.get()!=f)items.put(f.getID(),new FoodRef(f));}
    public static void initialize(KahluaTable data){
        if(!server())return;
        double schema=ClockHistory.number(data.rawget("schema"),1);
        if(schema!=1&&schema!=2&&schema!=3)throw new IllegalStateException("Unsupported OFP save schema "+schema);
        reset(); houses.initialize(data,now());ready=true;
        System.out.println("[PlayerAndSavehouseOfflinePreservation] ACTIVE "+VERSION+": login-only personal food protection, server storage food authority, offline condition and Vanilla generator protection, protocol "+PROTOCOL);
    }
    public static void reset(){GeneratorProtection.locations.clear();states.clear();items.clear();ClientItems.reset();dirty.clear();vehicles.clear();epoch=System.currentTimeMillis();nextPulse=0;lastPulseWorld=Float.NaN;ready=false;}
    public static void observeHouse(zombie.iso.areas.SafeHouse house){if(server()&&ready)houses.observe(house,now());}
    public static void changedHouses(){if(server()&&ready)houses.refresh(now());else houses.refreshNeeded=true;}
    public static boolean beforeAge(Food food){
        if(server()&&loading.get()>0)return true;
        if(personal(food))return false;
        if(client()){clientCalls++;Access.freezingOnly(food,now());return true;}
        if(!server())return false;
        if(loading.get()>0)return true;
        if(food.isRotten()) {
            // Only Vanilla's configured age-based removal still needs a terminal age clock.
            float time=now(),from=food.getLastAged();
            if(zombie.SandboxOptions.instance.daysForRottenFoodRemoval.getValue()<0||
                    Float.isFinite(from)&&from>=0&&time>=from&&time-from<AGE_STEP) {
                Access.freezingOnly(food,time);return true;
            }
            return false; // native cleanup accounting only, no house/personal history or publication
        }
        ageCalls++;
        if(!food.canAge()){Access.freezingOnly(food,now());return true;}
        float time=now(),from=food.getLastAged();
        if(forcing.get()==0&&Float.isFinite(from)&&from>=0&&time>=from&&time-from<AGE_STEP){Access.freezingOnly(food,time);return true;}
        if(!ready){Access.freezingOnly(food,time);return true;}
        if(houses.refreshNeeded||time<houses.lastTime)houses.refresh(time);
        State state=state(food);state.partialPause=false;register(food);
        ItemContainer root=root(food.getContainer());
        IsoGridSquare square=storageSquare(root);
        // A moving vehicle/root is settled against the last observed location before adopting its new one.
        int x=square==null?Integer.MIN_VALUE:square.getX(),y=square==null?Integer.MIN_VALUE:square.getY();
        double pause=0;int policyX=x,policyY=y;
        if(root!=null&&square!=null){
            int oldX=state.knownLocation&&state.root!=null&&state.root.get()==root?state.x:x;
            int oldY=state.knownLocation&&state.root!=null&&state.root.get()==root?state.y:y;
            int[] override=positionOverride.get();if(override!=null){oldX=override[0];oldY=override[1];}
            policyX=oldX;policyY=oldY;pause=houses.pause(oldX,oldY,from,time);
        }
        if(state.root==null||state.root.get()!=root)state.root=root==null?null:new WeakReference<>(root);state.x=x;state.y=y;state.knownLocation=square!=null;
        if(pause>0){
            protectedCalculations++;
            if(pause>=time-from)food.setLastAged(time);
            else{state.partialPause=true;state.beforePartial=food.getAge();state.partialFrom=from;state.partialX=policyX;state.partialY=policyY;}
        }
        ageCalculations++;
        return false;
    }
    public static void afterAge(Food food,boolean skipped){
        if(!server()||skipped||loading.get()>0||personal(food))return;
        State s=states.get(food);
        if(food.isRotten()&&(s==null||s.terminal))return;
        if(s==null)s=state(food);
        if(s.partialPause){
            float time=now();double allowed=allowedHours(s,s.partialFrom,time);
            ItemContainer container=food.getOutermostContainer();
            if(food.isFrozen())allowed=0;
            else if(container!=null&&(container.isFridge()||container.isFreezer())){
                double factor=Access.foodFactor(food,"getFridgeFactor");
                if(container.getSourceGrid()!=null&&container.getSourceGrid().haveElectricity())allowed*=factor;
                else {int cutoff=zombie.SandboxOptions.instance.getElecShutModifier()*24;
                    if(cutoff>=0&&s.partialFrom<cutoff){double split=Math.min(time,cutoff);allowed=allowedHours(s,s.partialFrom,split)*factor+allowedHours(s,split,time);}
                }
            }
            food.setAge((float)(s.beforePartial+allowed*Access.foodFactor(food,"getFoodRotSpeed")/24.0));s.partialPause=false;
        }
        changed(food,false);
    }
    static double allowedHours(State s,double from,double to){
        if(to<=from)return 0;
        double pause=houses.pause(s.partialX,s.partialY,from,to);
        return Math.max(0,to-from-pause);
    }
    static void changed(Food food,boolean urgent){
        if(!food.canAge()||personal(food))return;
        State s=state(food);boolean alreadyRotten=s.terminal&&food.isRotten();s.terminal=food.isRotten();
        if(s.age!=food.getAge()||s.last!=food.getLastAged()){
            boolean boundary=(s.age<food.getOffAge())!=(food.getAge()<food.getOffAge())||(s.age<food.getOffAgeMax())!=(food.getAge()<food.getOffAgeMax());
            s.age=food.getAge();s.last=food.getLastAged();s.revision++;
            if(!alreadyRotten&&profile.get()==0&&s.age!=s.publishedAge&&(urgent||boundary||now()-s.publishedAt>=PUBLISH_STEP))dirty.put(food.getID(),new FoodRef(food));
        } else if(!alreadyRotten&&urgent&&profile.get()==0)dirty.put(food.getID(),new FoodRef(food));
    }
    public static void settle(Food food){if(food.isRotten())return;forcing.set(forcing.get()+1);try{food.updateAge(false);}finally{forcing.set(forcing.get()-1);}}
    static ItemContainer root(ItemContainer container){
        ItemContainer c=container;
        for(int depth=0;c!=null&&depth<64;depth++){
            InventoryItem bag=c.getContainingItem();
            if(bag==null||bag.getContainer()==null||"floor".equals(bag.getContainer().getType()))return c;
            ItemContainer next=bag.getContainer();if(next==c)return null;c=next;
        }
        return c;
    }
    static IsoGridSquare storageSquare(ItemContainer root){
        if(root==null)return null;
        IsoObject parent=root.getParent();
        if(parent instanceof IsoGameCharacter||parent instanceof IsoDeadBody||root.isCorpse())return null;
        InventoryItem bag=root.getContainingItem();
        if(bag!=null&&bag.getWorldItem()!=null)return bag.getWorldItem().getSquare();
        if("floor".equals(root.getType()))return null;
        return parent==null?null:parent.getSquare();
    }
    static IsoPlayer owner(Food food){ItemContainer c=root(food.getContainer());return c!=null&&c.getParent() instanceof IsoPlayer p?p:null;}
    public static boolean personal(InventoryItem item){return item!=null&&personal(item.getContainer());}
    public static boolean personal(ItemContainer container){ItemContainer outer=root(container);return outer!=null&&outer.getParent() instanceof IsoPlayer;}
    static void releasePersonal(Food food){
        ClientItems.discard(food);items.remove(food.getID());dirty.remove(food.getID());states.remove(food);
        if(food.hasModData())food.getModData().rawset(STAMP,null);
    }
    static void walk(ItemContainer root,Consumer<Food> action){
        if(root==null)return;
        ArrayDeque<ItemContainer> stack=new ArrayDeque<>();Set<ItemContainer> visited=Collections.newSetFromMap(new IdentityHashMap<>());stack.push(root);
        while(!stack.isEmpty()){ItemContainer c=stack.pop();if(!visited.add(c))continue;for(InventoryItem item:c.getItems()){
            if(item instanceof Food f)action.accept(f);
            if(item instanceof InventoryContainer bag&&bag.getInventory()!=null)stack.push(bag.getInventory());
        }}
    }
    public static void loadEnter(){loading.set(loading.get()+1);}
    public static void loadExit(){loading.set(Math.max(0,loading.get()-1));}
    public static void playerLoaded(IsoPlayer player){
        if((!server()&&!client())||player.isDead())return;
        Object saved=player.getModData().rawget(KEY);
        double checkpoint=saved instanceof KahluaTable table?ClockHistory.number(table.rawget("lastOnline"),Double.NaN):Double.NaN;
        float time=now();
        double offline=Double.isFinite(checkpoint)?Math.max(0,time-checkpoint):0;
        walk(player.getInventory(),food->{
            // Profile preparation is the login boundary. Age and the freezing clock stay intact.
            if(server()){
                float last=food.getLastAged();
                food.setLastAged(!Double.isFinite(checkpoint)||!Float.isFinite(last)||last<0?time:(float)Math.min(time,last+offline));
            }
            releasePersonal(food);
        });
        checkpoint(player,time);
    }
    static void checkpoint(IsoPlayer player,float time){
        Object old=player.getModData().rawget(KEY);KahluaTable data=old instanceof KahluaTable t?t:LuaManager.platform.newTable();
        data.rawset("schema",2.0);data.rawset("lastOnline",(double)time);player.getModData().rawset(KEY,data);
    }
    public static void playerSaving(IsoPlayer player){
        if((!server()&&!client())||loading.get()>0||player.isDead())return;
        if(server()&&profile.get()==0&&GameServer.IDToPlayerMap.get(player.getOnlineID())!=player)return;
        // Persist the native end-of-online state, including food in closed nested bags.
        // Preparing an already corrected login profile must not settle/advance its contents.
        if(profile.get()==0)walk(player.getInventory(),food->{food.updateAge(false);releasePersonal(food);});
        checkpoint(player,now());
    }
    public static void playerConnected(){
        if(!server()||!ready)return;
        changedHouses();
    }
    public static boolean beforeMove(InventoryItem item,ItemContainer target){
        if(personal(item)){
            if(server()&&ready&&loading.get()==0&&item.getContainer()!=target&&!personal(target)){
                if(item instanceof Food food)food.updateAge(false);
                else if(item instanceof InventoryContainer bag)walk(bag.getInventory(),food->food.updateAge(false));
            }
            return false;
        }
        if(!server()||!ready||loading.get()>0||item.getContainer()==target)return false;
        if(item.getContainer()==null&&item.getWorldItem()==null){
            if(item instanceof Food f){if(!state(f).transferred)return false;}
            else if(item instanceof InventoryContainer bag){boolean[] moved={false};walk(bag.getInventory(),f->{State s=states.get(f);if(s!=null&&s.transferred)moved[0]=true;});if(!moved[0])return false;}
            else return false;
        }
        Consumer<Food> moving=f->{settle(f);state(f).transferred=true;};
        if(item instanceof Food f)moving.accept(f);else if(item instanceof InventoryContainer bag)walk(bag.getInventory(),moving);
        return true;
    }
    public static void afterMove(InventoryItem item,boolean settled){
        if(loading.get()>0)return;
        Consumer<Food> update=f->{
            if(personal(f)){releasePersonal(f);return;}
            if(!client())register(f);
            if(server()&&ready){State s=state(f);if(settled||s.transferred)f.setLastAged(now());if(item.getContainer()!=null||item.getWorldItem()!=null)s.transferred=false;s.knownLocation=false;s.root=null;changed(f,true);}
            else if(client())ClientItems.moved(f);
        };
        if(item instanceof Food f)update.accept(f);else if(item instanceof InventoryContainer bag)walk(bag.getInventory(),update);
    }
    public static void containerLoaded(ItemContainer container){
        if(loading.get()>0)return;
        walk(container,f->{if(personal(f)){releasePersonal(f);return;}register(f);if(client())applyPending(f);});
    }
    public static void vehicleMoved(BaseVehicle vehicle){
        if(!server()||!ready)return;
        int x=(int)Math.floor(vehicle.getX()),y=(int)Math.floor(vehicle.getY());int[] old=vehicles.get(vehicle);
        if(old==null){vehicles.put(vehicle,new int[]{x,y});return;}
        if(old[0]==x&&old[1]==y)return;
        if(houses.region(old[0],old[1])!=houses.region(x,y)){
            positionOverride.set(old);
            try{for(int i=0;i<vehicle.getPartCount();i++){var part=vehicle.getPartByIndex(i);if(part!=null)walk(part.getItemContainer(),FoodAuthority::settle);}}
            finally{positionOverride.remove();}
        }
        old[0]=x;old[1]=y;
    }
    public static void fullSave(Food food){
        if(personal(food))return;
        if(!server()||!ready||loading.get()>0)return;
        if(food.getContainer()!=null||food.getWorldItem()!=null)settle(food);
        changed(food,false);State s=state(food);
        KahluaTable stamp=LuaManager.platform.newTable();stamp.rawset("epoch",(double)epoch);stamp.rawset("revision",(double)s.revision);
        food.getModData().rawset(STAMP,stamp);
    }
    public static void fullLoad(Food food){
        if(loading.get()>0)return;
        if(server()){
            Object stamp=food.getModData().rawget(STAMP);
            if(stamp instanceof KahluaTable table&&(long)ClockHistory.number(table.rawget("epoch"),0)==epoch)
                state(food).revision=Math.max(1,(long)ClockHistory.number(table.rawget("revision"),1));
            FoodRef known=items.get(food.getID());Food original=known==null?null:known.get();
            if(loading.get()==0&&original!=null&&original!=food){settle(original);food.setAge(original.getAge());food.setLastAged(original.getLastAged());state(food).revision=state(original).revision;state(food).age=food.getAge();state(food).last=food.getLastAged();}
            if(profile.get()==0&&loading.get()==0)register(food);
        } else if(client()){
            Object raw=food.getModData().rawget(STAMP);
            // Personal full-item data has no storage-authority stamp. Do not replay a late
            // storage snapshot while its container/owner is still being deserialized.
            if(!(raw instanceof KahluaTable stamp)){ClientItems.discard(food);return;}
            register(food);
            Snapshot row=new Snapshot((long)ClockHistory.number(stamp.rawget("epoch"),0),(long)ClockHistory.number(stamp.rawget("revision"),0),food.getAge(),food.getLastAged());receive(food.getID(),row,food);
            applyPending(food);
        }
    }
    static Snapshot snapshot(Food food){changed(food,false);State s=state(food);return new Snapshot(epoch,s.revision,food.getAge(),food.getLastAged());}
    static void receive(int id,Snapshot row,Food food){ClientItems.receive(id,row,food);}
    static void applyPending(Food food){ClientItems.applyPending(food);}
    static void apply(Food food,Snapshot row){
        if(personal(food))return;
        ClientItems.stamp(food,row);
        boolean boundary=(food.getAge()<food.getOffAge())!=(row.age()<food.getOffAge())||(food.getAge()<food.getOffAgeMax())!=(row.age()<food.getOffAgeMax());
        applying.set(applying.get()+1);try{food.setAge(row.age());food.setLastAged(row.last());}finally{applying.set(applying.get()-1);}
        ItemContainer c=food.getContainer();if(c!=null)c.setDrawDirty(true);
        if(boundary)zombie.Lua.LuaEventManager.triggerEvent("OnContainerUpdate",food);
    }
    public static boolean rejectClientAge(InventoryItem item){return client()&&!personal(item)&&applying.get()==0&&loading.get()==0;}
    public static void pulse(){
        if(!server()||!ready)return;
        long tick=System.nanoTime();if(tick<nextPulse)return;nextPulse=tick+250_000_000L;
        if(Float.isFinite(lastPulseWorld)&&now()<lastPulseWorld)houses.refresh(now());
        lastPulseWorld=now();
        for(int n=0;n<256;n++){FoodRef ref=(FoodRef)collected.poll();if(ref==null)break;items.remove(ref.id,ref);}
        Wire.flush(8,64);
    }
    public static String metrics(){return "ageCalls="+ageCalls+" calculations="+ageCalculations+" protected="+protectedCalculations+" clientSuppressions="+clientCalls+" packets="+packets+" rows="+rows+" extensionBytes="+extensionBytes+" pending="+dirty.size()+" conditionBlocks="+ConditionProtection.prevented+" generatorEvaluations="+GeneratorProtection.evaluations+" generatorStops="+GeneratorProtection.stops+" generatorStarts="+GeneratorProtection.starts+" generatorAssociations="+GeneratorProtection.associations;}
}
