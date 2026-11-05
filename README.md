# PotitoSubastas para Minecraft 1.21.1 (Arclight/Bukkit)

Plugin de subastas con monedas fisicas y premio tangible. Requiere Java 21.

## Instalar y compilar
1. Sube todo el contenido del ZIP a un repositorio GitHub (conservando `.github/workflows/compilar.yml`).
2. En GitHub: **Actions > Compilar PotitoSubastas > Run workflow**.
3. Descarga el **artifact PotitoSubastas-1.21.1**, extrae el archivo `.jar` y colócalo en `plugins/`.
4. Reinicia el servidor por completo. No uses `/reload`.

## Configuracion inicial (obligatoria)
Como los IDs exactos de las monedas modded no se han entregado, el administrador debe sostener **cada objeto original**, uno a la vez, en su mano principal y ejecutar:
- `/subasta registrar jade` (10 puntos)
- `/subasta registrar azul` (100 puntos)
- `/subasta registrar gris` (1000 puntos)

El plugin reconoce exactamente el tipo de objeto y sus metadatos registrados. Los registros se guardan en `plugins/PotitoSubastas/estado.yml`.

## Uso
- **Admin:** sostener en la mano principal el lote que sera el premio (una unidad o una pila). `/subasta iniciar` retira ese lote del inventario y abre una ronda.
- **Jugador:** `/subasta saldo`, `/subasta actual`, `/subasta pujar 1000`.
- **Admin:** `/subasta cancelar` devuelve el premio y la puja depositada.

No se aceptan pujas que no puedan pagarse en monedas exactas (no hay cambio). Ejemplo: 1 moneda gris no puede pagar una puja de 100 puntos.

El conteo de 10 segundos **empieza con la primera puja** y se reinicia con cada puja que la supera. El jugador que supera una puja deja sus monedas en deposito; al ser superado, recupera su deposito. Al finalizar, el ganador recibe el premio y el vendedor recibe las monedas.

Si el servidor se reinicia, la ronda se cancela y sus objetos pendientes se devuelven cuando los jugadores se conecten. Si no hay espacio en inventario, el excedente se deja en el suelo junto al jugador. Conserva copias de seguridad de `estado.yml` y el mundo.

## Permisos
`potitosubastas.admin` (por defecto OP). Los comandos de jugadores no requieren permisos extra.

## Compatibilidad
Compila contra la API Paper 1.21.1, pero debe probarse en Arclight/Fabric, que puede registrar ítems de mods de forma distinta. Si no identifica una moneda al usar `/subasta saldo`, informa el mod/ID y la versión de Arclight para adaptar la deteccion.

## Aviso
El ZIP contiene el **código fuente**, no un `.jar` ya compilado. Se ofrece un workflow de GitHub Actions listo para compilarlo, ya que este entorno no puede descargar las dependencias de Paper.
