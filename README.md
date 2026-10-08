# Niebla Mística 1.2.0

Mod de Minecraft Java **1.20.1 + Forge 47.3.x** (Java 17).

La Niebla Mística es un evento sobrenatural que aparece de forma espontánea en el Overworld. Cuando empieza, la visibilidad se reduce progresivamente mediante profundidad de niebla real, el color del mundo se desatura hacia un gris frío y aparecen pequeñas partículas suspendidas. Además, los monstruos cercanos se vuelven ligeramente más rápidos y ocurren eventos ambientales ocasionales dentro de la niebla.

## Qué hace

- Evento controlado por el servidor y sincronizado con todos los jugadores.
- Aparición espontánea (8 % cada 30 s por defecto), duración aleatoria de 1 a 3 minutos.
- Estado guardado en el mundo: si cierras y abres, la niebla sigue donde se quedó.
- Entrada y salida **suaves** (≈5 s de transición) en vez de un corte brusco.
- Solo se nota en el Overworld; bajo tierra se suaviza; agua y lava conservan su niebla normal.
- Partículas ambientales discretas alrededor del jugador.
- Monstruos hostiles cercanos reciben Velocidad I mientras están dentro de la niebla.
- Sonidos ambientales localizados y ocasionales durante el evento.
- Corrientes frías ocasionales que ralentizan brevemente al jugador.
- La niebla ya no usa una capa negra plana sobre toda la pantalla.
- Avisos en el chat al empezar y terminar.

## Comandos (operador, nivel 2)

```text
/mistica start            (duración aleatoria)
/mistica start <segundos> (duración concreta, 5-7200)
/mistica stop
/mistica status
```

## Configuración

Archivo `config/nieblamistica-common.toml`:

| Opción | Por defecto | Qué hace |
|---|---|---|
| `spontaneous` | true | Si es false, solo aparece con `/mistica start` |
| `chancePercent` | 8.0 | Probabilidad (%) en cada comprobación |
| `checkIntervalSeconds` | 30 | Cada cuánto se comprueba |
| `minDurationSeconds` / `maxDurationSeconds` | 60 / 180 | Rango de duración |
| `notInPeaceful` | true | No aparece sola en Pacífico |
| `playSound` | true | Sonido al empezar |

## Compilar

Con GitHub Actions (pestaña **Actions** → último *Build* → artefacto `niebla-mistica`), o en tu PC con JDK 17 y Gradle 8.1.x: `gradle build`. El `.jar` queda en `build/libs/`.

## Licencia

MIT.
