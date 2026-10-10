# BouncyCastle: only the lightweight HPKE and ChaCha20-Poly1305 classes are used,
# referenced statically, so R8 can shrink the rest. Silence warnings for the JCA
# provider parts that refer to classes absent on Android.
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.bouncycastle.x509.util.LDAPStoreHelper

# Services and receivers are referenced from the manifest and kept by AAPT rules.
