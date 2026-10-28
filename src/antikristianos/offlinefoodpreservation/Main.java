package antikristianos.offlinefoodpreservation;

public final class Main {
    public static void main(String[] args){
        SelfBuiltSafehouses.installFromLoader();
        FoodAuthority.supported=true;
        System.out.println("[PlayerAndSavehouseOfflinePreservation] Java "+FoodAuthority.VERSION+" loaded; protocol "+FoodAuthority.PROTOCOL+".");
    }
}
