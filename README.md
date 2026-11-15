# FirewallDNS

Firewall DNS local para Android, creado por **Candido y Elizabeth**.

La aplicación usa el servicio Android `VpnService` para filtrar consultas DNS clásicas compatibles y permitir listas de bloqueo, reglas manuales, selección de DNS upstream y exclusiones por aplicación.

## Alcance y límites importantes

- Es un **filtro DNS**, no un firewall de paquetes completo. No controla de forma general todas las conexiones TCP/UDP ni los puertos de cada aplicación.
- DNS cifrado (DoH sobre HTTPS y DoT sobre TLS) no se inspecciona ni se puede bloquear de forma fiable solo por su dirección IP sin afectar otros servicios.
- El manejador actual procesa consultas DNS por UDP y rechaza DNS sobre TCP; tampoco reensambla paquetes IP fragmentados ni procesa cabeceras de extensión IPv6.
- La indicación de protección de la app se refiere al filtrado DNS, no a una garantía de que toda la conexión del dispositivo esté protegida.

Para un firewall por aplicación completo hace falta diseñar y probar un motor VPN de reenvío de paquetes con control de conexiones y políticas explícitas de TCP/UDP. No se debe anunciar esa capacidad hasta implementar y validar ese motor en dispositivos reales.

## Seguridad y robustez

- Cola de procesamiento acotada para limitar el uso de memoria durante ráfagas de paquetes.
- Validación de longitud UDP y descarte explícito de paquetes fragmentados que este motor no puede reensamblar.
- El servicio aclara que DoH/DoT no se inspecciona.
