-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.bigsinger.tvminesweeper.game.** { *; }
-keep class com.bigsinger.tvminesweeper.tv.DoubleClickDetector { *; }
-keep class com.bigsinger.tvminesweeper.tv.DoubleClickDetector$* { *; }
-keep class com.bigsinger.tvminesweeper.ui.*Dialog { *; }
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
