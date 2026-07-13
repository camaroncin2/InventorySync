# Guía de configuración de portales (Cretania)

Esta guía explica cómo crear y configurar los portales físicos que conectan
los servidores de la red (lobby, survival1, etc.) con el sistema nuevo de
`PortalManager`. El teletransporte es siempre **instantáneo y literal**: a
las coords que configures, o si no configuras nada, al propio bloque del
portal — no hay RTP (teletransporte aleatorio) ni búsqueda de superficie.

## 0. Antes de empezar

- El jar `cretania-neoforge-<version>.jar` con el sistema de portales debe
  estar instalado en **todos** los servidores que vayan a tener un portal
  (origen y destino). Compílalo con [`build.bat`](build.bat) en la raíz del
  proyecto y súbelo a `mods/` de cada server.
- Revisa que no queden sistemas viejos interfiriendo en el mismo lugar
  (zonas de AuthMod, `[[trigger_zone]]` antiguos, etc. — ver
  [Conflictos con sistemas viejos](#conflictos-con-sistemas-viejos)).
- Necesitas permisos de operador (`/op`) en el server donde construyas el
  portal.

## 1. Construir el portal físico

Construye un portal de obsidiana **normal** (mínimo 4×5) y enciéndelo con
un encendedor, como cualquier portal del Nether. Cretania usa esos mismos
bloques (`minecraft:nether_portal`) — no hace falta ningún bloque especial.

Colócalo donde quieras que los jugadores "entren" para viajar a otro
servidor (o a otro punto del mismo servidor).

## 2. Registrar el portal

Párate **dentro** de los bloques del portal (en cualquier parte) y ejecuta:

```
/portales crear <nombre>
```

Ejemplo: `/portales crear a_survival1`.

Esto busca los bloques de portal en un radio de 4 alrededor tuyo y
registra automáticamente toda la caja conectada (flood-fill). También
puedes usar el botón verde **"Registrar portal aquí"** del menú principal
(`/portales`), que te pide el nombre en un yunque.

> **Importante:** al crearse, el portal queda con el servidor destino en
> `lobby` por defecto (el primero de la lista) y **sin coords**. Todavía
> no está configurado — sigue al paso 3.

## 3. Configurar destino — todo desde el menú `/portales`

Ejecuta:

```
/portales
```

Haz clic en el portal recién creado para abrir su menú de configuración.
Ahí tienes **dos cosas separadas** que debes configurar — es el error más
común, así que léelo con atención:

### 3.1. Asignar el SERVIDOR destino (obligatorio)

En la fila superior del menú (papeles blancos / tinte lima = el actual),
haz **clic en el servidor** al que quieres que este portal transfiera.

- Si el servidor no aparece en la lista, usa **"+ Agregar servidor"** y
  escribe su nombre — debe coincidir **exactamente** con el nombre
  registrado en Velocity (`velocity.toml`, sección `[servers]`).
- El botón se aplica en tiempo real, no hace falta reiniciar nada.

**Si te saltas este paso, el portal se queda apuntando a `lobby` por
defecto — y si el portal está construido en el propio lobby, terminas
"transfiriendo" al jugador al mismo servidor donde ya está, y parece que
el portal no hace nada.** Este fue exactamente el problema que tuvimos al
probar `a_survival1`: solo se habían configurado las coords, pero nunca se
hizo clic en el servidor destino.

### 3.2. Configurar las coords destino (opcional pero recomendado)

Clic en **"Coords destino"** (lodestone) y escribe en el yunque:

```
x y z
```
o, si además quieres fijar hacia dónde mira el jugador al llegar:
```
x y z yaw pitch
```

- Si **no** configuras coords, el jugador aterriza literalmente en el
  bloque del portal de origen (mismo punto, mismo servidor destino) — rara
  vez es lo que quieres al cambiar de mundo, así que en la práctica
  **casi siempre conviene poner coords**.
- Escribe `clear` en el yunque para quitar las coords ya puestas.

### 3.3. Otras opciones del menú

| Botón | Qué hace |
|---|---|
| Renombrar portal | Cambia el nombre (yunque) |
| Eliminar portal | Shift+clic para borrar el registro |
| Volver | Vuelve al listado principal |

## 4. Caso especial: portal dentro del mismo servidor

Si el portal debe teletransportar **sin salir del servidor actual** (sin
pasar por Velocity), asígnale el servidor especial `local` en el paso
3.1. Con coords configuradas, el jugador aparece exactamente ahí; sin
coords, se queda en el mismo punto (poco útil salvo para pruebas).

## 5. Recomendado: portal de "vuelta" en el servidor destino

Para que el viaje se sienta consistente en ambos sentidos, construye
también un portal físico en el servidor destino, en las coords que
pusiste como destino del primero, y regístralo apuntando de vuelta al
servidor de origen (repite todo el proceso, invirtiendo servidor y
coords). Así el jugador llega literalmente parado junto a un portal de
regreso.

## 6. Verificar

```
/portales lista
```

Debe mostrar algo así:

```
a_survival1 [minecraft:overworld] (100,64,200)-(102,66,202) → survival1 [Coords (120.0, 65.0, -340.0)]
```

Confirma que:
- El **servidor** (después de la flecha) es el que quieres, no `lobby` por
  defecto.
- El **modo** (`[Coords (...)]` o `[Portal de origen...]`) es el que
  esperas.

## 7. Comandos equivalentes (sin usar el menú)

```
/portales crear <nombre>                          → registrar portal cercano
/portales lista                                    → ver todos los portales
/portales servidor <nombre> <server>               → asignar servidor destino
/portales coords <nombre> <x> <y> <z> [yaw pitch]  → fijar coords destino
/portales coords <nombre> clear                    → quitar coords (→ propio portal)
/portales renombrar ... (usa el menú, no hay comando directo)
/portales eliminar <nombre>                        → borrar el registro
/portales servidores                               → listar servers conocidos
/portales servidores add <server>                  → agregar server a la lista
/portales servidores remove <server>                → quitarlo de la lista
/portales reload                                   → recargar config/invsync-portals.toml desde disco
```

Todo se persiste en `config/invsync-portals.toml` **de cada servidor** y
se aplica al instante.

## Conflictos con sistemas viejos

Antes de dar por buena una prueba, verifica que no exista **otro** sistema
todavía transfiriendo en la misma zona:

- **AuthMod (`config/authmod-zones.toml`)**: tiene su propio sistema de
  "zonas" (`[[zone]]`) que transfiere a los jugadores al cruzar un
  rectángulo, totalmente aparte de los portales de Cretania. Si el portal
  viejo estaba ahí, revisa que la zona correspondiente ya no exista en ese
  archivo.
- **`[[trigger_zone]]` en `invsync-return-zone.toml`**: sistema
  eliminado, solo genera un warning al arrancar, no hace nada — se puede
  ignorar o limpiar el archivo, no afecta a los portales nuevos.
- **`[zone]` (zona de retorno "outside") en `invsync-return-zone.toml`**:
  si existe, transfiere a `returnServer` cuando el jugador sale de un
  rectángulo — es un sistema aparte de los portales, revisa que no se
  superponga con el área de tu portal nuevo.

## Errores comunes (checklist rápido)

- [ ] ¿El jar nuevo está instalado en **ambos** servidores (origen y
      destino)?
- [ ] ¿Le asignaste el **servidor** destino en el menú (no solo las
      coords)? — `/portales lista` debe mostrar el server correcto después
      de la flecha.
- [ ] ¿El nombre del servidor coincide exactamente con el de
      `velocity.toml`?
- [ ] ¿Configuraste las **coords** destino, o aceptas que el jugador
      aparezca en el mismo punto del portal de origen?
- [ ] ¿Ya no queda ninguna zona vieja (AuthMod, `[zone]`) activa en el
      mismo lugar?
