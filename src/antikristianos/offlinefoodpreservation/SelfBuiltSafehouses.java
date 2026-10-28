package antikristianos.offlinefoodpreservation;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.function.Consumer;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import zombie.characters.IsoGameCharacter;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridOcclusionData;
import zombie.iso.IsoGridSquare;
import zombie.iso.LightingJNI;
import zombie.iso.areas.isoregion.IsoRegions;
import zombie.iso.areas.isoregion.data.DataRoot;
import zombie.iso.areas.isoregion.metagrid.WorldRegionToMetaGrid;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.network.GameServer;
import zombie.network.ServerMap;

/** Supplies Vanilla's existing player-building conversion on the server. No claim policy lives here. */
public final class SelfBuiltSafehouses {
    private static final String TARGET="zombie/iso/areas/isoregion/metagrid/WorldRegionToMetaGrid";
    private static final String HELPER="antikristianos/offlinefoodpreservation/SelfBuiltSafehouses";
    private static volatile boolean installed;
    private static final Field rootField=field(IsoRegions.class,"dataRoot");
    private static final Field dirtyChunks=field(WorldRegionToMetaGrid.class,"dirtyChunks");
    private static final Field updateChunks=field(WorldRegionToMetaGrid.class,"updateChunks");
    private static final Method eachSquare=method("forEachSquare",Consumer.class);
    private static final Method assignRoom=method("lambda$updateSquares$0",IsoGridSquare.class);
    private static final Method recalcSquare=method("lambda$updateSquares$1",IsoGridSquare.class);
    private static final Consumer<IsoGridSquare> roomUpdater=square->invoke(assignRoom,null,square);
    private static final Consumer<IsoGridSquare> squareUpdater=square->invoke(recalcSquare,null,square);

    private static Field field(Class<?> type,String name){
        try{Field value=type.getDeclaredField(name);value.setAccessible(true);return value;}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static Method method(String name,Class<?> argument){
        try{Method value=WorldRegionToMetaGrid.class.getDeclaredMethod(name,argument);value.setAccessible(true);return value;}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    private static Object get(Field field,Object receiver){
        try{return field.get(receiver);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
    }
    private static void invoke(Method method,Object receiver,Object value){
        try{method.invoke(receiver,value);}catch(ReflectiveOperationException e){throw new IllegalStateException("Vanilla player-building operation failed",e);}
    }

    public static void installFromLoader(){
        if(!GameServer.server)return;
        try{install((Instrumentation)field(Class.forName("me.zed_0xff.zombie_buddy.Loader"),"g_instrumentation").get(null));}
        catch(ReflectiveOperationException e){throw new IllegalStateException("ZombieBuddy instrumentation unavailable",e);}
    }
    public static synchronized void install(Instrumentation instrumentation){
        if(installed)return;
        if(instrumentation==null)throw new IllegalStateException("Self-built safehouses require ZombieBuddy instrumentation");
        VisualCalls transformer=new VisualCalls();
        instrumentation.addTransformer(transformer,true);
        try{
            instrumentation.retransformClasses(WorldRegionToMetaGrid.class);
            if(!transformer.matched)throw new IllegalStateException("Unsupported Vanilla player-building visual call sites");
            installed=true;
            System.out.println("[PlayerAndSavehouseOfflinePreservation] Vanilla self-built building conversion enabled on server.");
        }catch(Exception e){instrumentation.removeTransformer(transformer);throw new IllegalStateException("Cannot enable Vanilla self-built safehouses",e);}
    }

    /** Called after Vanilla has published its completed region buffer, on the simulation thread. */
    public static void update(){
        if(!GameServer.server||!installed)return;
        DataRoot root=(DataRoot)get(rootField,null);
        if(root!=null)root.clientProcessBuildings();
    }

    /** Keep Vanilla's two square callbacks; supply loaded server chunks instead of local player maps. */
    @SuppressWarnings("unchecked")
    public static boolean updateServerSquares(WorldRegionToMetaGrid converter){
        if(!GameServer.server)return false;
        ArrayList<IsoChunk> chunks=(ArrayList<IsoChunk>)get(updateChunks,converter);
        chunks.clear();
        for(IsoGameCharacter.Location location:(ArrayList<IsoGameCharacter.Location>)get(dirtyChunks,converter)){
            IsoChunk chunk=ServerMap.instance.getChunk(location.x,location.y);
            if(chunk!=null)chunks.add(chunk);
        }
        invoke(eachSquare,converter,roomUpdater);
        invoke(eachSquare,converter,squareUpdater);
        return true;
    }

    // Narrow call-site wrappers preserve client/SP visuals and avoid graphics initialization on a server.
    public static void lightingChanged(){if(!GameServer.server)LightingJNI.buildingsChanged();}
    public static void occlusionChanged(){if(!GameServer.server)IsoGridOcclusionData.SquareChanged();}
    public static FBORenderCutaways cutaways(){return GameServer.server?null:FBORenderCutaways.getInstance();}
    public static void cutawayChanged(FBORenderCutaways renderer,IsoGridSquare square){if(!GameServer.server)renderer.squareChanged(square);}

    /** Rewrites only four graphics calls in the existing conversion; its grouping/geometry stays native. */
    public static final class VisualCalls implements ClassFileTransformer {
        volatile boolean matched;
        @Override public byte[] transform(ClassLoader loader,String name,Class<?> type,ProtectionDomain domain,byte[] bytes){
            if(!TARGET.equals(name))return null;
            ClassReader reader=new ClassReader(bytes);ClassWriter writer=new ClassWriter(reader,0);
            int[] counts=new int[4];
            reader.accept(new ClassVisitor(Opcodes.ASM9,writer){
                @Override public MethodVisitor visitMethod(int access,String methodName,String descriptor,String signature,String[] exceptions){
                    MethodVisitor next=super.visitMethod(access,methodName,descriptor,signature,exceptions);
                    if(!methodName.equals("clientProcessBuildings")||!descriptor.equals("(Ljava/util/ArrayList;)V"))return next;
                    return new MethodVisitor(Opcodes.ASM9,next){
                        @Override public void visitMethodInsn(int opcode,String owner,String name,String desc,boolean itf){
                            if(owner.equals("zombie/iso/LightingJNI")&&name.equals("buildingsChanged")&&desc.equals("()V")){
                                counts[0]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"lightingChanged",desc,false);
                            }else if(owner.equals("zombie/iso/IsoGridOcclusionData")&&name.equals("SquareChanged")&&desc.equals("()V")){
                                counts[1]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"occlusionChanged",desc,false);
                            }else if(owner.equals("zombie/iso/fboRenderChunk/FBORenderCutaways")&&name.equals("getInstance")&&desc.equals("()Lzombie/iso/fboRenderChunk/FBORenderCutaways;")){
                                counts[2]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"cutaways",desc,false);
                            }else if(owner.equals("zombie/iso/fboRenderChunk/FBORenderCutaways")&&name.equals("squareChanged")&&desc.equals("(Lzombie/iso/IsoGridSquare;)V")){
                                counts[3]++;super.visitMethodInsn(Opcodes.INVOKESTATIC,HELPER,"cutawayChanged","(Lzombie/iso/fboRenderChunk/FBORenderCutaways;Lzombie/iso/IsoGridSquare;)V",false);
                            }else{
                                if(owner.equals(HELPER)){
                                    if(name.equals("lightingChanged"))counts[0]++;
                                    if(name.equals("occlusionChanged"))counts[1]++;
                                    if(name.equals("cutaways"))counts[2]++;
                                    if(name.equals("cutawayChanged"))counts[3]++;
                                }
                                super.visitMethodInsn(opcode,owner,name,desc,itf);
                            }
                        }
                    };
                }
            },0);
            for(int count:counts)if(count!=1){installed=false;throw new IllegalStateException("Changed Vanilla player-building graphics call shape");}
            matched=true;return writer.toByteArray();
        }
    }
}
