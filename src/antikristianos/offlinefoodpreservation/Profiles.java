package antikristianos.offlinefoodpreservation;

import java.nio.*;
import java.util.*;
import zombie.characters.IsoPlayer;
import zombie.core.network.*;
import zombie.core.raknet.UdpConnection;
import zombie.iso.IsoWorld;
import zombie.network.*;

public final class Profiles {
    static final Map<Object,byte[]> prepared=Collections.synchronizedMap(new WeakHashMap<>());
    public static void prepare(Object packet){
        if(!FoodAuthority.server()||!Boolean.TRUE.equals(Access.get(packet,"isExist"))||Boolean.TRUE.equals(Access.get(packet,"isDead")))return;
        byte[] input=(byte[])Access.get(packet,"buffer");if(input==null||prepared.get(packet)==input)return;
        FoodAuthority.profile.set(FoodAuthority.profile.get()+1);
        IsoPlayer player=null;
        try{
            player=new IsoPlayer(IsoWorld.instance.currentCell,new zombie.characters.SurvivorDesc(false),0,0,0);
            player.load(ByteBuffer.wrap(input),(int)Access.get(packet,"worldVersion"));player.remote=true;
            int size=Math.max(65536,input.length+65536);ByteBuffer output;
            while(true){output=ByteBuffer.allocate(size);try{player.save(output);break;}catch(BufferOverflowException e){if(size>=64*1024*1024)throw e;size*=2;}}
            byte[] corrected=Arrays.copyOf(output.array(),output.position());Access.set(packet,"buffer",corrected);Access.set(packet,"worldVersion",IsoWorld.getWorldVersion());prepared.put(packet,corrected);
        }catch(Exception e){throw new IllegalStateException("OFP could not prepare authoritative character profile",e);}
        finally{
            if(player!=null&&player.getInventory()!=null){
                Set<zombie.inventory.ItemContainer> seen=Collections.newSetFromMap(new IdentityHashMap<>());
                ArrayDeque<zombie.inventory.ItemContainer> pending=new ArrayDeque<>();pending.push(player.getInventory());
                while(!pending.isEmpty()){
                    var container=pending.pop();if(!seen.add(container))continue;
                    container.removeItemsFromProcessItems();
                    for(var item:container.getItems()){
                        if(item instanceof zombie.inventory.types.Food food)FoodAuthority.states.remove(food);
                        if(item instanceof zombie.inventory.types.InventoryContainer bag&&bag.getInventory()!=null)pending.push(bag.getInventory());
                    }
                }
            }
            FoodAuthority.profile.set(FoodAuthority.profile.get()-1);
        }
    }
    public static zombie.characters.SurvivorDesc descriptorBefore(ByteBuffer buffer){
        if(!FoodAuthority.preparingProfile()||buffer.remaining()<4)return null;
        return IsoWorld.instance.survivorDescriptors.get(buffer.getInt(buffer.position()));
    }
    public static void descriptorAfter(zombie.characters.SurvivorDesc descriptor,zombie.characters.SurvivorDesc previous){
        if(!FoodAuthority.preparingProfile())return;
        int id=descriptor.getID();
        if(previous!=null)IsoWorld.instance.survivorDescriptors.put(id,previous);
        else IsoWorld.instance.survivorDescriptors.remove(id,descriptor);
    }
    static void requireSupported(){if(!FoodAuthority.supported)throw new IllegalStateException("OFP native baseline not supported");}
    public static void write(ByteBufferWriter b){requireSupported();b.putInt(Wire.MAGIC);b.putByte((byte)FoodAuthority.PROTOCOL);b.putLong(FoodAuthority.epoch);}
    public static void parse(ByteBufferReader b,IConnection connection){
        requireSupported();
        if(b.remaining()<13||b.getInt()!=Wire.MAGIC||(b.getByte()&255)!=FoodAuthority.PROTOCOL){
            if(connection instanceof UdpConnection udp)udp.forceDisconnect("PlayerAndSavehouseOfflinePreservation: matching Java component/protocol required");
            throw new IllegalStateException("PlayerAndSavehouseOfflinePreservation: matching Java component/protocol required on server and client");
        }
        long serverEpoch=b.getLong();
        if(GameClient.client&&FoodAuthority.epoch!=serverEpoch){FoodAuthority.reset();FoodAuthority.epoch=serverEpoch;}
    }
}
