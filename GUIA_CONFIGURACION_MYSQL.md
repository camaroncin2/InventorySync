# Guía de Configuración - Cretania Sync (MySQL)

## Requisitos Previos

- Servicio de hosting con soporte para **MySQL 8.0+** o **MariaDB 10.6+**
- Acceso al panel de administración de bases de datos (phpMyAdmin, cPanel, Pterodactyl, etc.)
- Servidores NeoForge 1.21.1 con el mod `cretania-neoforge-1.0.0.jar` instalado
- Proxy Velocity con el plugin `cretania-velocity-1.0.0.jar` instalado

---

## Paso 1: Crear la Base de Datos

### Opción A: Desde el panel de hosting (cPanel / Pterodactyl)

1. Accede al panel de tu hosting
2. Busca la sección **"Bases de Datos MySQL"** o **"Databases"**
3. Crea una nueva base de datos con el nombre: `cretania`
4. Crea un nuevo usuario de base de datos (ej: `cretania_user`) con una contraseña segura
5. Asigna **todos los privilegios** del usuario a la base de datos `cretania`

### Opción B: Desde la consola MySQL (acceso SSH)

```sql
-- Conectarse como root
mysql -u root -p

-- Crear la base de datos
CREATE DATABASE IF NOT EXISTS cretania
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Crear el usuario (cambia 'TU_CONTRASEÑA_SEGURA' por una real)
CREATE USER IF NOT EXISTS 'cretania_user'@'%' IDENTIFIED BY 'TU_CONTRASEÑA_SEGURA';

-- Si los servidores de Minecraft están en la misma máquina, usa localhost:
-- CREATE USER IF NOT EXISTS 'cretania_user'@'localhost' IDENTIFIED BY 'TU_CONTRASEÑA_SEGURA';

-- Asignar privilegios
GRANT ALL PRIVILEGES ON cretania.* TO 'cretania_user'@'%';
FLUSH PRIVILEGES;
```

> **Nota de seguridad:** Si tus servidores de Minecraft están en la misma máquina o red local que MySQL, usa `'localhost'` o `'127.0.0.1'` en lugar de `'%'` para mayor seguridad.

---

## Paso 2: Verificar la Conexión

Desde la máquina donde corren tus servidores de Minecraft, verifica que puedes conectarte:

```bash
mysql -h <HOST_MYSQL> -u cretania_user -p cretania
```

Si la conexión es exitosa, verás el prompt de MySQL. Escribe `exit` para salir.

---

## Paso 3: Configurar el Mod en los Servidores NeoForge

Cuando el mod se ejecuta por primera vez, genera automáticamente un archivo de configuración en cada servidor:

```
<carpeta_servidor>/config/cretaniasync-server.toml
```

### Editar el archivo de configuración

Abre `cretaniasync-server.toml` y modifica la sección `[mysql]`:

```toml
[mysql]
    # Host del servidor MySQL
    # - Si MySQL está en la misma máquina: "127.0.0.1" o "localhost"
    # - Si está en otra máquina de la red: usa la IP privada (ej: "10.0.0.5")
    # - Si usas un servicio externo: usa el hostname proporcionado
    host = "127.0.0.1"

    # Puerto de MySQL (por defecto 3306)
    port = 3306

    # Nombre de la base de datos que creaste en el Paso 1
    database = "cretania"

    # Usuario de MySQL
    username = "cretania_user"

    # Contraseña del usuario
    password = "TU_CONTRASEÑA_SEGURA"
```

### Configuración del pool de conexiones (opcional)

```toml
[pool]
    # Para un servidor con menos de 20 jugadores simultáneos, los defaults están bien.
    # Para servidores grandes (50+ jugadores), considera aumentar:
    maxSize = 10
    minIdle = 2
    connectionTimeout = 5000
```

### Configuración de sincronización (opcional)

```toml
[sync]
    # Segundos máximos para esperar la carga de datos
    timeoutSeconds = 10

    # Si falla la sincronización, ¿desconectar al jugador?
    # true = más seguro (evita duplicaciones)
    # false = el jugador se queda pero sin datos sincronizados
    kickOnFailure = true
```

---

## Paso 4: Configurar el Plugin en Velocity

El plugin de Velocity (`cretania-velocity-1.0.0.jar`) **no requiere configuración de MySQL**. Solo coordina la comunicación entre servidores.

Asegúrate de que:

1. El plugin está en la carpeta `plugins/` de Velocity
2. `velocity.toml` tiene configurado `player-info-forwarding-mode = "modern"`
3. Los servidores NeoForge tienen el forwarding secret correcto

---

## Paso 5: Configurar los Servidores NeoForge para Velocity

En cada servidor NeoForge, el archivo `server.properties` debe tener:

```properties
online-mode=false
enforce-secure-profile=false
allow-flight=true
```

Y la configuración del mod NeoVelocity (o similar) debe tener el **mismo forwarding secret** que `velocity.toml`:

```toml
# config/neovelocity-common.toml (o equivalente)
[forwarding]
    forwarding-secret = "TU_SECRET_AQUI"
```

---

## Estructura de la Tabla (Referencia)

El mod crea automáticamente la tabla `player_data` al iniciar. No necesitas crearla manualmente. Su estructura es:

```sql
CREATE TABLE IF NOT EXISTS player_data (
    uuid         VARCHAR(36) PRIMARY KEY,
    player_name  VARCHAR(16) NOT NULL,
    nbt_data     LONGTEXT NOT NULL,
    server_name  VARCHAR(64) NOT NULL,
    updated_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

| Campo | Descripción |
|-------|-------------|
| `uuid` | UUID del jugador (clave primaria) |
| `player_name` | Nombre del jugador |
| `nbt_data` | Datos del jugador en Base64 (inventario, vida, XP, etc.) |
| `server_name` | Último servidor donde se guardaron los datos |
| `updated_at` | Fecha/hora de la última actualización |

---

## Troubleshooting

### Error: "Base de datos no disponible"
- Verifica que MySQL/MariaDB esté corriendo
- Verifica que el host, puerto, usuario y contraseña sean correctos
- Si usas un hosting externo, verifica que tu IP esté en la lista blanca (whitelist)

### Error: "Timeout de conexión"
- Aumenta `connectionTimeout` en el config (ej: `10000` para 10 segundos)
- Si MySQL está en otra máquina, verifica que el firewall permite conexiones al puerto 3306

### Los datos no se sincronizan entre servidores
- Verifica que **TODOS** los servidores apuntan a la **misma base de datos**
- Verifica que el plugin de Velocity esté instalado y activo
- Revisa los logs del servidor: busca `[Cretania]` para mensajes de diagnóstico

### El jugador aparece bajo tierra al cambiar de servidor
- Esto fue corregido en la versión actual. Si persiste, asegúrate de usar la última versión del mod.

---

## Ejemplo de Configuración Completa (Hosting Típico)

### Escenario: 2 servidores NeoForge + 1 Velocity + MySQL en la misma máquina

```
Máquina: 192.168.1.100
├── Velocity (puerto 25577)
├── NeoForge Lobby (puerto 25565)
├── NeoForge Survival (puerto 25566)
└── MySQL (puerto 3306)
```

**Config en ambos servidores NeoForge** (`cretaniasync-server.toml`):
```toml
[mysql]
    host = "127.0.0.1"
    port = 3306
    database = "cretania"
    username = "cretania_user"
    password = "MiContraseñaSegura2024!"
```

### Escenario: MySQL en servidor dedicado separado

```
Máquina A (192.168.1.10): Velocity + NeoForge servers
Máquina B (192.168.1.20): MySQL dedicado
```

**Config en los servidores NeoForge**:
```toml
[mysql]
    host = "192.168.1.20"
    port = 3306
    database = "cretania"
    username = "cretania_user"
    password = "MiContraseñaSegura2024!"
```

**En MySQL (Máquina B)**, el usuario debe aceptar conexiones desde la Máquina A:
```sql
CREATE USER 'cretania_user'@'192.168.1.10' IDENTIFIED BY 'MiContraseñaSegura2024!';
GRANT ALL PRIVILEGES ON cretania.* TO 'cretania_user'@'192.168.1.10';
FLUSH PRIVILEGES;
```

---

## Datos Sincronizados

El mod sincroniza automáticamente:
- Inventario completo (incluye armadura y offhand)
- Puntos de vida (HP)
- Puntos de experiencia (XP)
- Efectos de pociones activos
- Capabilities de mods (Create, TerraFirmaCraft, etc.)
- Datos de hambre y saturación

**NO se sincronizan** (son específicos por servidor):
- Posición del jugador
- Dimensión actual
- Punto de spawn
- Advancements/logros
