package antikristianos.offlinefoodpreservation;

import java.lang.reflect.*;
import java.lang.invoke.*;
import java.util.concurrent.ConcurrentHashMap;
import zombie.inventory.types.Food;
import zombie.inventory.ItemContainer;

public final class Access {
    private static final ConcurrentHashMap<String,Field> fields=new ConcurrentHashMap<>();
    private static final MethodHandle freezing;
    static {
        try {Method m=Food.class.getDeclaredMethod("updateFreezing",ItemContainer.class,float.class);m.setAccessible(true);freezing=MethodHandles.lookup().unreflect(m);}
        catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}
    }
    static Field field(Object object,String name) {
        return fields.computeIfAbsent(object.getClass().getName()+"#"+name,k->{
            for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){}
            throw new IllegalStateException("Missing native field "+k);
        });
    }
    static Object get(Object object,String name){try{return field(object,name).get(object);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    static void set(Object object,String name,Object value){try{field(object,name).set(object,value);}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
    private static final ConcurrentHashMap<String,Method> foodMethods=new ConcurrentHashMap<>();
    static float foodFactor(Food food,String name){
        try{Method method=foodMethods.computeIfAbsent(name,k->{try{Method m=Food.class.getDeclaredMethod(k);m.setAccessible(true);return m;}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}});return ((Number)method.invoke(food)).floatValue();}
        catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
    }
    public static void freezingOnly(Food food,float now) {
        try {freezing.invokeExact(food,food.getOutermostContainer(),now);}
        catch(Throwable e){throw new IllegalStateException("Vanilla freezing update failed",e);}
    }
}
