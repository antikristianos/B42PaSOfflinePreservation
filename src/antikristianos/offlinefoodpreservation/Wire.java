package antikristianos.offlinefoodpreservation;

import java.lang.reflect.Method;
import java.util.*;
import zombie.inventory.*;
import zombie.inventory.types.Food;
import zombie.iso.IsoGridSquare;
import zombie.characters.IsoPlayer;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.core.raknet.UdpConnection;
import zombie.network.*;
import zombie.network.fields.ContainerID;
import zombie.network.packets.*;

/** Versioned trailer on Vanilla ItemStats, with optional same-root age batches. */
public final class Wire {
    static final int MAGIC=0x4f465032, MAX_ROWS=64;
    public record Row(int id,FoodAuthority.Snapshot state) {}
    static final Map<Object,List<Row>> payloads=Collections.synchronizedMap(new WeakHashMap<>());
    static final ThreadLocal<List<Row>> sending=new ThreadLocal<>();
    static long nextFailureLog;
    static final Method applyStats;
    static {try{applyStats=ItemStatsPacket.class.getDeclaredMethod("applyItemStats",InventoryItem.class);applyStats.setAccessible(true);}catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}}
    static boolean foodPacket(Object packet){return Boolean.TRUE.equals(Access.get(packet,"isFood"));}
    static ItemContainer container(Object packet){return ((ContainerID)Access.get(packet,"containerId")).getContainer();}
    static Food find(ItemContainer root,int id){
        var ref=FoodAuthority.items.get(id);Food f=FoodAuthority.client()?ClientItems.find(root,id):ref==null?null:ref.get();
        if(f!=null&&f.getID()==id&&FoodAuthority.root(f.getContainer())==FoodAuthority.root(root))return f;
        InventoryItem item=root==null?null:root.getItemWithIDRecursiv(id);
        if(item instanceof Food found){FoodAuthority.register(found);return found;}return null;
    }
    public static void prepare(Object[] values){
        if(!FoodAuthority.server()&&!FoodAuthority.client())return;
        if(values.length!=2||!(values[1] instanceof InventoryItem item))return;
        ItemContainer root=FoodAuthority.root(item.getContainer());
        // Native setData resolves the supplied container before our exit advice runs.
        if(root!=null&&!"floor".equals(root.getType()))values[0]=root;
    }
    public static void setData(Object packet,Object[] values){
        payloads.remove(packet);
        if(values.length!=2||!(values[1] instanceof Food food)||FoodAuthority.personal(food))return;
        if(FoodAuthority.server()){
            FoodAuthority.settle(food);
            List<Row> batch=sending.get();payloads.put(packet,batch==null?List.of(new Row(food.getID(),FoodAuthority.snapshot(food))):batch);
        }
    }
    public static void write(Object packet,ByteBufferWriter b){
        if(!foodPacket(packet))return;
        List<Row> rows=FoodAuthority.server()?payloads.get(packet):null;
        // Forwarded client packets must obtain their age from the server object.
        if(FoodAuthority.server()&&(rows==null||rows.isEmpty())){
            Food f=find(container(packet),(int)Access.get(packet,"id"));
            if(f!=null&&!FoodAuthority.personal(f)){FoodAuthority.settle(f);rows=List.of(new Row(f.getID(),FoodAuthority.snapshot(f)));}
        }
        writeRows(b,rows==null?List.of():rows);
    }
    static void writeRows(ByteBufferWriter b,List<Row> rows){
        if(rows.size()>MAX_ROWS)throw new IllegalArgumentException("OFP batch too large");
        b.putInt(MAGIC);b.putByte((byte)FoodAuthority.PROTOCOL);b.putByte((byte)rows.size());
        b.putLong(FoodAuthority.epoch);
        for(Row row:rows){b.putInt(row.id());b.putLong(row.state().revision());b.putFloat(row.state().age());b.putFloat(row.state().last());}
        FoodAuthority.extensionBytes+=14L+20L*rows.size();
    }
    public static void parse(Object packet,ByteBufferReader b,IConnection connection){
        payloads.remove(packet);if(!foodPacket(packet))return;
        List<Row> rows=readRows(b);
        // Ignore every client-supplied age, including well-formed malicious trailers.
        if(!FoodAuthority.server())payloads.put(packet,rows);
    }
    static List<Row> readRows(ByteBufferReader b){
        if(b.remaining()<14||b.getInt()!=MAGIC||(b.getByte()&255)!=FoodAuthority.PROTOCOL)throw new IllegalStateException("PlayerAndSavehouseOfflinePreservation protocol mismatch");
        int count=b.getByte()&255;long epoch=b.getLong();
        if(count>MAX_ROWS||b.remaining()<count*20)throw new IllegalArgumentException("Truncated OFP ItemStats trailer");
        ArrayList<Row> rows=new ArrayList<>(count);
        for(int i=0;i<count;i++){
            int id=b.getInt();FoodAuthority.Snapshot state=new FoodAuthority.Snapshot(epoch,b.getLong(),b.getFloat(),b.getFloat());
            if(!state.valid())throw new IllegalArgumentException("Invalid authoritative food state");rows.add(new Row(id,state));
        }
        return rows;
    }
    static void applyStats(Object packet,InventoryItem food){try{applyStats.invoke(packet,food);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    public static boolean processClient(Object packet){
        if(!FoodAuthority.client())return false;
        if(!foodPacket(packet)){
            ItemContainer root=container(packet);int id=(int)Access.get(packet,"id");
            if(root==null||root.getItemWithID(id)!=null)return false;
            InventoryItem nested=root.getItemWithIDRecursiv(id);if(nested==null)return false;
            applyStats(packet,nested);return true;
        }
        ItemContainer root=container(packet);Food primary=find(root,(int)Access.get(packet,"id"));
        if(primary!=null)applyStats(packet,primary);
        List<Row> rows=payloads.get(packet);
        if(rows!=null)for(Row row:rows)FoodAuthority.receive(row.id(),row.state(),find(root,row.id()));
        return true;
    }
    public static boolean processServer(Object packet,UdpConnection connection){
        if(!FoodAuthority.server())return false;
        ItemContainer container=container(packet);int id=(int)Access.get(packet,"id");
        InventoryItem item=foodPacket(packet)?find(container,id):container==null?null:container.getItemWithIDRecursiv(id);
        if(item==null)return foodPacket(packet);
        if(FoodAuthority.personal(item)){
            // User-approved boundary: condition correction for OFFLINE owners only.
            IsoPlayer owner=(IsoPlayer)FoodAuthority.root(container).getParent();
            boolean online=GameServer.IDToPlayerMap.get(owner.getOnlineID())==owner;
            boolean corrected=false;
            if(!online){
                corrected=ConditionProtection.reject(item,(int)Access.get(packet,"condition"));
                if(corrected)Access.set(packet,"condition",item.getCondition());
            }
            // Online owners retain native fields; neither path supplies personal age rows.
            if(container.getItemWithID(id)!=null&&!corrected)return false;
            applyStats(packet,item);
            if(container.getSquare()!=null)((INetworkPacket)packet).sendToRelativeClients(PacketTypes.PacketType.ItemStats,corrected?null:connection,container.getSquare().getX(),container.getSquare().getY());
            return true;
        }
        boolean corrected=ConditionProtection.reject(item,(int)Access.get(packet,"condition"));
        if(corrected)Access.set(packet,"condition",item.getCondition());
        if(item instanceof Food f){
            FoodAuthority.settle(f);applyStats(packet,f);FoodAuthority.changed(f,true);
            payloads.put(packet,List.of(new Row(f.getID(),FoodAuthority.snapshot(f))));
        }else if(corrected||container.getItemWithID(id)==null)applyStats(packet,item);
        else return false;
        ItemContainer root=FoodAuthority.root(item.getContainer());IsoGridSquare square=root==null?null:root.getSquare();
        // A rejected condition decrease must not be forwarded as if it had succeeded.
        // Include its origin in the correction, while leaving the packet's other statistics intact.
        if(square!=null)((INetworkPacket)packet).sendToRelativeClients(PacketTypes.PacketType.ItemStats,corrected?null:connection,square.getX(),square.getY());
        return true;
    }
    static void retry(List<Food> foods){
        for(Food food:foods)FoodAuthority.dirty.putIfAbsent(food.getID(),new FoodAuthority.FoodRef(food));
    }
    static void sendFailure(Exception failure){
        long time=System.nanoTime();
        if(time>=nextFailureLog){
            nextFailureLog=time+30_000_000_000L;
            System.out.println("[PlayerAndSavehouseOfflinePreservation] ItemStats send deferred; update retained for retry: "+failure);
        }
    }
    /** True means handed to Vanilla's reliable transport, not acknowledged by the client. */
    static boolean send(UdpConnection connection,ItemContainer root,Food primary){
        if(!connection.isFullyConnected())return false;
        boolean started=false;
        try{
            // The convenience INetworkPacket.send catches failures and returns void.
            // Use its native packet format and transport with an observable transaction.
            PacketTypes.PacketType type=PacketTypes.PacketType.ItemStats;
            INetworkPacket packet=connection.getPacket(type);
            packet.setData(root,primary);
            ByteBufferWriter writer=connection.startPacket();started=true;
            type.doPacket(writer);packet.write(writer);
            type.send(connection);started=false;
            return true;
        }catch(Exception failure){
            if(started)try{connection.cancelPacket();}catch(Exception cancel){failure.addSuppressed(cancel);}
            sendFailure(failure);return false;
        }
    }
    static IsoGridSquare square(ItemContainer root,Food primary){
        if(primary.getWorldItem()!=null)return primary.getWorldItem().getSquare();
        if(root==null)return null;
        IsoGridSquare square=root.getSquare();
        if(square==null&&root.getContainingItem()!=null&&root.getContainingItem().getWorldItem()!=null)
            square=root.getContainingItem().getWorldItem().getSquare();
        return square;
    }
    static void flush(int packetBudget,int rowBudget){
        if(FoodAuthority.dirty.isEmpty()||packetBudget<=0||rowBudget<=0)return;
        rowBudget=Math.min(rowBudget,MAX_ROWS);
        // A bounded prefix avoids scanning all loaded food or the entire pending queue.
        // Removing just this prefix lets deferred work rotate behind other pending items.
        Map<Object,List<Food>> groups=new LinkedHashMap<>();
        var it=FoodAuthority.dirty.entrySet().iterator();int remaining=packetBudget*rowBudget;
        while(it.hasNext()&&remaining-->0){Food f=it.next().getValue().get();it.remove();if(f==null||FoodAuthority.personal(f))continue;
            ItemContainer root=FoodAuthority.root(f.getContainer());
            groups.computeIfAbsent(root==null?f:root,k->new ArrayList<>()).add(f);
        }
        int attempted=0;
        for(var group:groups.entrySet())for(int start=0;start<group.getValue().size();start+=rowBudget){
            List<Food> foods=group.getValue().subList(start,Math.min(start+rowBudget,group.getValue().size()));
            if(attempted>=packetBudget){retry(foods);continue;}
            boolean complete=false;
            try{
                ItemContainer root=group.getKey() instanceof ItemContainer c?c:null;Food primary=foods.get(0);
                if(GameServer.udpEngine==null)continue;
                List<UdpConnection> recipients=new ArrayList<>();
                IsoGridSquare square=square(root,primary);if(square==null)continue;
                // World-storage recipients only; personal inventories never enter this queue.
                attempted++;
                for(UdpConnection connection:GameServer.udpEngine.connections)
                    if(connection.isFullyConnected()&&connection.isRelevantTo(square.getX(),square.getY()))recipients.add(connection);
                if(recipients.isEmpty()){complete=true;continue;}
                if(root==null)root=new ItemContainer("floor",square,null);
                List<Row> rows=new ArrayList<>(foods.size());
                for(Food f:foods){FoodAuthority.settle(f);rows.add(new Row(f.getID(),FoodAuthority.snapshot(f)));}
                sending.set(rows);
                boolean delivered=true;
                for(UdpConnection connection:recipients)if(!send(connection,root,primary))delivered=false;
                if(!delivered)continue;
                for(int i=0;i<foods.size();i++){
                    Food f=foods.get(i);FoodAuthority.State s=FoodAuthority.state(f);FoodAuthority.Snapshot published=rows.get(i).state();
                    s.publishedAge=published.age();s.publishedAt=FoodAuthority.now();
                    // A newer change created while serializing must not be erased.
                    if(s.revision==published.revision())FoodAuthority.dirty.remove(f.getID());
                }
                FoodAuthority.packets++;FoodAuthority.rows+=rows.size();
                complete=true;
            }catch(Exception failure){sendFailure(failure);}
            finally{sending.remove();if(!complete)retry(foods);}
        }
    }
}
