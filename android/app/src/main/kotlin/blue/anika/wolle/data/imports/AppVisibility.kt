package blue.anika.wolle.data.imports

import javax.inject.Inject
import javax.inject.Singleton

/** Whether the app is on screen; [MainActivity][blue.anika.wolle.MainActivity] keeps it current. */
@Singleton
class AppVisibility @Inject constructor() {
    @Volatile
    var visible: Boolean = false
        private set

    fun started() {
        visible = true
    }

    fun stopped() {
        visible = false
    }
}
