# FirewallDNS

Firewall DNS local para Android, creado por Candido y Elizabeth.

Esta app usa un servicio `VpnService` para interceptar y filtrar consultas DNS compatibles, así como aplicar reglas personalizadas, listas externas y exclusiones por aplicación.

## Qué es exactamente

- Es un filtro DNS / firewall de dominios, no un firewall completo de todas las conexiones del sistema.
- Bloquea consultas DNS tradicionales y permite personalizar dominios bloqueados o permitidos.
- El tráfico cifrado como DoH/DoT no se inspecciona de forma fiable en este enfoque.
- La protección se centra en la resolución de nombres y en la gestión de reglas de dominio.

## Límite realista

- No reemplaza un firewall de paquete completo ni bloquea todo el tráfico TCP/UDP del dispositivo.
- La protección se entiende como seguridad DNS y filtrado de dominios.
- Para un control total por aplicación y por puerto, se requeriría un diseño de VPN más avanzado y pruebas de red reales.

## Créditos

- Desarrollo y diseño: Candido y Elizabeth
- Proyecto: FirewallDNS

## Uso recomendado

- Mantén activado solo el filtrado DNS necesario.
- Usa listas externas con cuidado y valida reglas personalizadas.
- Si quieres mayor protección, combina este filtro con políticas de red y controles del sistema.
