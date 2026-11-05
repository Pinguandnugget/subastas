# PotitoSubastas 1.1 - Iconos y nombres de mod

Actualizacion de PotitoSubastas para Arclight 1.21.1. Pujas, depositos, premios y registros conservan el comportamiento anterior.

NOVEDADES:
- Iconos reales de Dedita Azul y Dedita Gris usando un resource pack adicional (descargar ZIP separado).
- El Jade muestra de momento la moneda verde ya incluida en el mod; no la textura de un jade de datapack.
- Los nombres de premios del mod se muestran con claves traducibles al cliente (ej. item.ruleta.totem_nutria = Tótem Nutria).
- El resource pack debe estar instalado por cada cliente para ver los iconos.

INSTALACION:
1. Conserva copia de seguridad de plugins/PotitoSubastas/estado.yml, ya contiene los registros de monedas.
2. Compila este proyecto con GitHub Actions (incluido .github/workflows/compilar.yml).
3. Sustituye el JAR antiguo por el nuevo y reinicia.
4. Mezcla la carpeta assets/potitosubastas del ZIP ResourcePack con el resource pack actual y distribuyelo a los jugadores.
5. Comprueba /subasta saldo, /subasta iniciar y /subasta actual.

El pack iconos no lleva datos de servidor y no sustituye el mod ni el datapack.
