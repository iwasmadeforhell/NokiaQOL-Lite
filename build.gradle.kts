plugins { id("net.fabricmc.fabric-loom") }

base { archivesName = "nokiaqol-lite" }
version = providers.gradleProperty("mod_version").get()

repositories { mavenCentral() }

dependencies {
	minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
	implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
	implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")
	// mp3 decoding for custom sounds (pure Java, 145 KB).
	implementation("javazoom:jlayer:1.0.1")
	include("javazoom:jlayer:1.0.1")
}

tasks.processResources {
	inputs.property("version", version)
	filesMatching("fabric.mod.json") { expand("version" to version) }
}
tasks.withType<JavaCompile>().configureEach { options.release = 25 }
java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }
