# No app classes are reached via reflection or JavaScript interfaces.

# Tor (Orbit VPN): native code reads TorService's fields and calls its methods by name, and the
# control connection is matched against replies by class; keep both libraries as they are.
-keep class org.torproject.jni.** { *; }
-keep class net.freehaven.tor.control.** { *; }
