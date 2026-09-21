# X1VEMFPS v2.0.0

FiveM-inspired Android performance HUD for Minecraft 1.21.10 / Fabric.

## HUD
- FPS
- CPU usage and optional average CPU frequency
- GPU utilization and optional GPU clock when Android exposes it
- GPU/thermal temperature fallback
- Network ping from the Minecraft connection
- Transparent outlined cells, no persistent background panel
- Configurable border thickness (1-4px), font size, padding, update interval
- Individual metric toggles

## Settings
Press **X** to open the X1VEMFPS settings menu. X is registered as a normal Minecraft keybind and can be changed in Minecraft Controls.

Settings are saved to `config/x1vemfps.json`.

## Android notes
The mod reads Android/Linux sysfs/procfs metrics when the launcher/device permits access. Some Android builds restrict GPU utilization, clock, or thermal files; those values show `--` instead of inventing a number.

## Build
Run `./gradlew build` in an environment with the Gradle distribution and Maven dependencies available. The source was prepared here, but this environment could not download the Gradle distribution because outbound network access was unavailable.
