plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
}

group = "com.example"
version = "1.0-BETA"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21)) // Paper 1.21 uses Java 17+
}

repositories {
    mavenCentral()
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.dmulloy2.net/repository/public/") // ✅ ProtocolLib repo
}

dependencies {
    paperweight.paperDevBundle("1.21.6-R0.1-SNAPSHOT")
    implementation("net.kyori:adventure-text-minimessage:4.14.0")
    compileOnly("me.clip:placeholderapi:2.11.6")
    compileOnly("com.comphenix.protocol:ProtocolLib:5.3.0") // ✅ ProtocolLib support
}
