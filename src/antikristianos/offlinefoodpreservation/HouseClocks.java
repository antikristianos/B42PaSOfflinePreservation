package antikristianos.offlinefoodpreservation;

import java.util.*;
import se.krka.kahlua.vm.KahluaTable;
import zombie.Lua.LuaManager;
import zombie.iso.areas.SafeHouse;
import zombie.network.GameServer;

public final class HouseClocks {
    public static final class House {
        final String key; final int x,y,w,h; final ClockHistory clock=new ClockHistory(), presence=new ClockHistory();
        boolean present; long generation; KahluaTable saved;
        House(String key,int x,int y,int w,int h) { this.key=key;this.x=x;this.y=y;this.w=w;this.h=h; }
        boolean contains(int px,int py) {return px>=x&&px<x+w&&py>=y&&py<y+h;}
    }
    final Map<String,House> houses=new LinkedHashMap<>();
    final Map<Long,ArrayList<House>> cells=new HashMap<>();
    KahluaTable data;
    double lastTime, generatorSince;
    boolean refreshNeeded=true;
    static long cell(int x,int y) {return ((long)Math.floorDiv(x,32)<<32)|(Math.floorDiv(y,32)&0xffffffffL);}
    void initialize(KahluaTable table,double now) {
        houses.clear();cells.clear();data=table;lastTime=now;
        generatorSince=ClockHistory.number(table.rawget("generatorProtectionSince"),now);
        Object saved=table.rawget("houses");
        if(saved instanceof KahluaTable rows) {
            var it=rows.iterator();while(it.advance()) if(it.getValue() instanceof KahluaTable row) {
                House h=new House(String.valueOf(it.getKey()),(int)ClockHistory.number(row.rawget("x"),0),(int)ClockHistory.number(row.rawget("y"),0),(int)ClockHistory.number(row.rawget("w"),0),(int)ClockHistory.number(row.rawget("h"),0));
                if(h.w<=0||h.h<=0)continue;
                h.generation=(long)ClockHistory.number(row.rawget("generation"),0);h.clock.read(row);h.present=Boolean.TRUE.equals(row.rawget("exists"));
                if(row.rawget("presence") instanceof KahluaTable existence)h.presence.read(existence);
                else if(h.present)h.presence.setPaused(true,now);
                houses.put(h.key,h);
            }
        }
        double savedAt=ClockHistory.number(table.rawget("at"),now);
        if(now<savedAt){generatorSince=Math.min(generatorSince,now);for(House h:houses.values()){h.clock.rollback(now);h.presence.rollback(now);}}
        // A stopped server has no advancing world time. Re-establish occupancy at the saved boundary.
        refreshNeeded=true; refresh(now);reindex();persist();
    }
    void refresh(double now) {
        if(data==null)return;
        if(now<lastTime) {generatorSince=Math.min(generatorSince,now);for(House h:houses.values()){h.clock.rollback(now);h.presence.rollback(now);}lastTime=now;persist();}
        lastTime=now;
        Set<String> online=new HashSet<>();
        for(var p:GameServer.IDToPlayerMap.values())if(p!=null)online.add(p.getUsername());
        Set<String> present=new HashSet<>(); boolean changed=false, geometry=false;
        for(SafeHouse safe:SafeHouse.getSafehouseList()) {
            String key=safe.getX()+":"+safe.getY()+":"+safe.getW()+":"+safe.getH();present.add(key);
            House h=houses.get(key);
            if(h==null) {h=new House(key,safe.getX(),safe.getY(),safe.getW(),safe.getH());houses.put(key,h);index(h);geometry=true;}
            boolean paused=!online.contains(safe.getOwner());
            if(paused)for(String resident:safe.getPlayers())if(online.contains(resident)){paused=false;break;}
            if(!h.present||paused!=!Double.isNaN(h.clock.open)){if(!h.present)h.generation++;h.clock.setPaused(paused,now);h.presence.setPaused(true,now);h.present=true;changed=true;}
        }
        for(House h:houses.values())if(h.present&&!present.contains(h.key)){h.clock.setPaused(false,now);h.presence.setPaused(false,now);h.present=false;changed=true;}
        if(changed||geometry){persist();}
        refreshNeeded=false;
        if(changed||geometry)GeneratorProtection.changedHouses();
    }
    void observe(SafeHouse safe,double now){
        String key=safe.getX()+":"+safe.getY()+":"+safe.getW()+":"+safe.getH();House h=houses.get(key);
        if(h==null||!h.present||now<lastTime){refresh(now);return;}
        boolean paused=safe.getPlayerConnected()==0;
        if(paused==!Double.isNaN(h.clock.open))return;
        h.clock.setPaused(paused,now);lastTime=now;persist(h);GeneratorProtection.changedHouses();
    }
    void persist() {
        if(data==null)return;
        for(House h:houses.values())persist(h);
        data.rawset("schema",3.0);data.rawset("at",lastTime);data.rawset("generatorProtectionSince",generatorSince);
    }
    void persist(House h) {
        if(data==null)return;
        Object existing=data.rawget("houses");
        KahluaTable rows=existing instanceof KahluaTable t?t:LuaManager.platform.newTable();
        if(h.saved==null){
            h.saved=LuaManager.platform.newTable();
            h.saved.rawset("x",(double)h.x);h.saved.rawset("y",(double)h.y);
            h.saved.rawset("w",(double)h.w);h.saved.rawset("h",(double)h.h);
        }
        h.saved.rawset("generation",(double)h.generation);h.saved.rawset("exists",h.present);h.saved.rawset("active",Double.isNaN(h.clock.open));
        h.clock.write(h.saved);
        KahluaTable existence=h.saved.rawget("presence") instanceof KahluaTable t?t:LuaManager.platform.newTable();
        h.presence.write(existence);h.saved.rawset("presence",existence);rows.rawset(h.key,h.saved);
        data.rawset("houses",rows);data.rawset("schema",3.0);data.rawset("at",lastTime);data.rawset("generatorProtectionSince",generatorSince);
    }
    void reindex() {
        cells.clear();
        for(House h:houses.values())index(h);
    }
    void index(House h) {
        for(int x=Math.floorDiv(h.x,32);x<=Math.floorDiv(h.x+h.w-1,32);x++)
            for(int y=Math.floorDiv(h.y,32);y<=Math.floorDiv(h.y+h.h-1,32);y++) {
                long key=((long)x<<32)|(y&0xffffffffL);
                // Replacing only the affected bucket gives each generator a local geometry token.
                var previous=cells.get(key);var next=previous==null?new ArrayList<House>():new ArrayList<>(previous);
                next.add(h);cells.put(key,next);
            }
    }
    public double pause(int x,int y,double from,double now) {
        var list=cells.get(cell(x,y));if(list==null)return 0;
        double total=0;for(House h:list)if(h.contains(x,y))total+=h.clock.between(from,now);
        return Math.min(Math.max(0,now-from),total);
    }
    boolean protectedNow(int x,int y) {
        var list=cells.get(cell(x,y));if(list==null)return false;
        boolean offline=false;
        for(House h:list)if(h.present&&h.contains(x,y)) {
            if(Double.isNaN(h.clock.open))return false;
            offline=true;
        }
        return offline;
    }
    House region(int x,int y){var list=cells.get(cell(x,y));if(list!=null)for(House h:list)if(h.contains(x,y))return h;return null;}
    boolean hasHistory(int x,int y) {var list=cells.get(cell(x,y));if(list!=null)for(House h:list)if(h.contains(x,y))return true;return false;}
}
