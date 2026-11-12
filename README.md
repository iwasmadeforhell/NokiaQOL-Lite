# NokiaQOL Lite

A small, client-side Fabric mod for Hypixel SkyBlock (Minecraft 26.2). About 230 KB.

- **Ore skins** – retexture Mithril, Titanium, Gemstones, Tungsten, Umber, Hardstone, Coal and other ores. Each group has its own on/off switch, break sound, and places it applies in (Dwarven Mines / Glacite Tunnels, Crystal Hollows, Glacite Mineshafts, everywhere else).
- **Your own textures and sounds** – a `NokiaQOL` folder is created in the game directory with `textures/` (`.png`) and `sounds/` (`.ogg`, `.mp3`, `.wav`). Drop files in, then pick them from the dropdowns in the menu. Textures are swapped live, no resource reload.
- **Pickaxe ability alert** – sound and title when "Maniac Miner is now available!" (or any ability) shows up in chat.

Open the menu with **Right Ctrl** or `/nokialite`. `/nokialite debug` prints where the mod thinks you are.

## Build

Needs JDK 25.

```
./gradlew build
```

The jar is in `build/libs/`.

## License

CC0-1.0
