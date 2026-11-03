package io.tvos.setup;
import java.util.ArrayList;
import java.util.List;
/** Only runtime grants; special accessibility access always needs a separate system screen. */
public final class SetupPermissionPlan {
    private SetupPermissionPlan() { }
    public static String[] runtime(int sdk, boolean fine, boolean tvRequired, boolean tvGranted) {
        List<String> result = new ArrayList<>();
        if (!fine) {
            if (sdk >= 31) result.add("android.permission.ACCESS_COARSE_LOCATION");
            result.add("android.permission.ACCESS_FINE_LOCATION");
        }
        if (tvRequired && !tvGranted) result.add("android.permission.READ_TV_LISTINGS");
        return result.toArray(new String[0]);
    }
}
