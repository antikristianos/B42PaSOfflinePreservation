package antikristianos.offlinefoodpreservation;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.zip.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;

/** Lossless canonical intervals, primitive prefix index and incremental compressed save blocks. */
public final class ClockHistory {
    static final int BLOCK_SIZE=256, BLOCK_BYTES=BLOCK_SIZE*16;
    static final class Values {
        double[] values=new double[16];int size;
        int size(){return size;}
        boolean isEmpty(){return size==0;}
        double get(int i){return values[i];}
        void set(int i,double v){values[i]=v;}
        void add(double v){if(size==values.length)values=Arrays.copyOf(values,size*2);values[size++]=v;}
        void truncate(int n){size=n;values=Arrays.copyOf(values,Math.max(16,n));}
    }
    final Values starts=new Values(),ends=new Values(),sums=new Values();
    double open=Double.NaN;
    KahluaTable persisted;int dirtyFrom;
    public void setPaused(boolean paused,double now) {
        if(!Double.isFinite(now))return;
        if(paused&&Double.isNaN(open))open=now;
        else if(!paused&&!Double.isNaN(open)){if(now>open)add(open,now);open=Double.NaN;}
    }
    void add(double start,double end) {
        if(!Double.isFinite(start)||!Double.isFinite(end)||end<=start)return;
        int last=ends.size()-1;
        if(last>=0&&start<starts.get(last))throw new IllegalArgumentException("Unordered protection intervals");
        if(last>=0&&start<=ends.get(last)) {
            // Adjacent/covered records become one interval; the replaced tail is removed on save.
            if(end>ends.get(last)){sums.set(last,sums.get(last)+end-ends.get(last));ends.set(last,end);dirtyFrom=Math.min(dirtyFrom,last);}
            return;
        }
        dirtyFrom=Math.min(dirtyFrom,starts.size());
        starts.add(start);ends.add(end);sums.add((last<0?0:sums.get(last))+end-start);
    }
    double totalAt(double at) {
        int low=0,high=ends.size();
        while(low<high){int mid=(low+high)>>>1;if(ends.get(mid)<=at)low=mid+1;else high=mid;}
        double sum=low==0?0:sums.get(low-1);
        if(low<starts.size())sum+=Math.max(0,at-starts.get(low));
        if(!Double.isNaN(open))sum+=Math.max(0,at-open);
        return sum;
    }
    public double between(double from,double to) {
        if(!Double.isFinite(from)||!Double.isFinite(to)||from<0||to<=from)return 0;
        return Math.min(to-from,Math.max(0,totalAt(to)-totalAt(from)));
    }
    void rollback(double now) {
        int n=0;while(n<starts.size()&&starts.get(n)<now)n++;
        if(n>0&&ends.get(n-1)>now){sums.set(n-1,sums.get(n-1)-(ends.get(n-1)-now));ends.set(n-1,now);}
        starts.truncate(n);ends.truncate(n);sums.truncate(n);dirtyFrom=Math.min(dirtyFrom,Math.max(0,n-1));
        if(!Double.isNaN(open))open=Math.min(open,now);
    }
    void read(KahluaTable record) {
        if(record.rawget("intervalBlocks") instanceof KahluaTable blocks) {
            for(int i=1;i<=blocks.len();i++)decode(String.valueOf(blocks.rawget(i)));
        } else if(record.rawget("intervals") instanceof KahluaTable table) {
            for(int i=1;i<=table.len();i++)if(table.rawget(i) instanceof KahluaTable span)
                add(number(span.rawget(1),Double.NaN),number(span.rawget(2),Double.NaN));
        }
        open=number(record.rawget("offlineSince"),Double.NaN);
    }
    void decode(String encoded) {
        try(var stream=new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            byte[] raw=stream.readNBytes(BLOCK_BYTES+1);
            if(raw.length==0||raw.length>BLOCK_BYTES||raw.length%16!=0)throw new IOException("Invalid interval block size");
            var bytes=ByteBuffer.wrap(raw);while(bytes.hasRemaining())add(bytes.getDouble(),bytes.getDouble());
        } catch(IOException|IllegalArgumentException e){throw new IllegalStateException("Invalid OFP history block",e);}
    }
    String encode(int from,int to) {
        var bytes=ByteBuffer.allocate((to-from)*16);
        for(int i=from;i<to;i++){bytes.putDouble(starts.get(i));bytes.putDouble(ends.get(i));}
        try(var result=new ByteArrayOutputStream()) {
            try(var stream=new DeflaterOutputStream(result)){stream.write(bytes.array());}
            return Base64.getEncoder().encodeToString(result.toByteArray());
        } catch(IOException e){throw new IllegalStateException(e);}
    }
    void write(KahluaTable record) {
        if(persisted==null){persisted=LuaManager.platform.newTable();dirtyFrom=0;}
        int count=(starts.size()+BLOCK_SIZE-1)/BLOCK_SIZE,previous=persisted.len();
        for(int block=dirtyFrom/BLOCK_SIZE;block<count;block++)
            persisted.rawset(block+1,encode(block*BLOCK_SIZE,Math.min(starts.size(),(block+1)*BLOCK_SIZE)));
        for(int block=previous;block>count;block--)persisted.rawset(block,null);
        dirtyFrom=Integer.MAX_VALUE;
        record.rawset("intervalBlocks",persisted);record.rawset("intervals",null);
        record.rawset("offlineSince",Double.isNaN(open)?null:open);
    }
    static double number(Object value,double fallback){return value instanceof Number n&&Double.isFinite(n.doubleValue())?n.doubleValue():fallback;}
}
