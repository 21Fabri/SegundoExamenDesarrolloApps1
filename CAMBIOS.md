# Jornadas del Sur — Guardia de mantenimiento

Resumen de los cambios hechos sobre el código recibido de la cátedra.
El primer commit del repositorio (`9fb17d5`) es la base **sin modificar**, para que
el diff de cada arreglo se lea contra ella.

---

## 41 · Programa sin señal

**Qué estaba mal.** `Catalogo` mostraba el error y la lista en ramas opuestas de un
`if`, así que una falla de red escondía el catálogo entero aunque Room tuviera los
datos guardados. Además, un único `String` de aviso no alcanzaba para distinguir
tres situaciones que piden respuestas distintas y que desde la tabla se ven iguales:
nunca se descargó nada, falló pero hay edición guardada, y se descargó la v4 que
está legítimamente vacía.

**Qué se hizo.** El repositorio recuerda en preferencias si alguna vez hubo una
descarga exitosa (`huboDescarga`). Con ese dato más el estado de falla, la pantalla
decide entre tres caminos:

| Situación | Qué muestra |
|---|---|
| Falló y nunca se descargó | Mensaje de error + botón Reintentar |
| Falló y hay edición guardada | Lista, detalle y favoritas + aviso de datos guardados |
| Se descargó la v4 | «Sin charlas programadas», estado que sobrevive al reabrir sin señal |

**Por qué en preferencias y no en una tabla.** Guardarlo en Room habría obligado a
cambiar el esquema y escribir una migración sobre instalaciones que ya tienen datos
de usuario. El README pide explícitamente conservarlos. Una preferencia resuelve lo
mismo sin tocar el esquema.

También se agregó un aviso en el detalle de una charla que ya no está en el programa,
que antes quedaba en blanco.

---

## 42 · Agenda personal

**Qué estaba mal.** Tres cosas distintas:

1. El DAO guardaba con `@Insert(onConflict = REPLACE)`, que en SQLite es *borrar e
   insertar*. Y `AgendaRepository` construía cada `Charla` sin pasar `favorita`, o
   sea con el default `false`. Resultado: **cada sincronización borraba todas las
   marcas**.
2. Nada borraba filas. Una charla retirada del programa (la 11 en v3) quedaba en la
   base para siempre.
3. Una marca tocada mientras la petición estaba en vuelo se perdía al llegar la
   respuesta.

**Qué se hizo.** `sincronizar()` aplica la instantánea completa dentro de una
transacción:

```kotlin
base.withTransaction {
    val favoritas = dao.idsFavoritas().toSet()
    if (fichas.isEmpty()) {
        dao.borrarTodas()
    } else {
        dao.borrarAusentes(fichas.map { it.id })
        dao.guardar(fichas.map { it.copy(favorita = it.id in favoritas) })
    }
}
```

**Decisiones que conviene poder defender:**

- **Una sola tabla, no una de favoritas aparte.** Separarlas parece más prolijo pero
  rompe la regla "si una charla se retira y después regresa, regresa sin marcar": la
  marca sobreviviría huérfana. Con una sola tabla, borrar la fila borra la marca y
  esa regla sale sola.
- **Las favoritas se leen *dentro* de la transacción**, no antes de `api.consultar()`.
  Si se leyeran antes, los 1,4 segundos de espera serían una ventana donde el toque
  del usuario se pierde.
- **`REPLACE` no se eliminó, se volvió inofensivo.** El problema nunca fue `REPLACE`
  en sí: era que las fichas se construían sin la marca. Ahora cada una se reescribe
  con su `favorita` ya puesta. Esto permitió no tocar el esquema.
- **El caso vacío va aparte** porque Room genera `DELETE FROM charlas WHERE id NOT IN ()`
  con una lista vacía, y eso no es SQL válido. Sin ese `if`, la v4 rompe en ejecución.

---

## 43 · Volver al punto de lectura

**Qué estaba mal.** El filtro de tema vivía en un `remember` pelado, así que se perdía
al rotar. El día vivía en un `MutableStateFlow` del ViewModel, que sobrevive la
rotación pero no la muerte del proceso.

**Qué se hizo.** Los dos pasan a `SavedStateHandle`, que se guarda junto al estado de
instancia. Eso cubre los tres requisitos de una sola vez: vuelven tras rotar, vuelven
tras `am kill`, y **una tarea nueva arranca sin filtros** porque no hay estado de
instancia que restaurar.

Guardarlos en preferencias habría roto justamente el último punto: los filtros
perseguirían al usuario para siempre.

No hizo falta una factory propia: `by viewModels()` usa `SavedStateViewModelFactory`,
que ya sabe construir un `AndroidViewModel(Application, SavedStateHandle)`.

---

## 44 · Control de descargas

**Qué estaba mal.** Había dos disparadores de descarga simultáneos: `onStart()` de la
Activity y un `LaunchedEffect(Unit)` en `Catalogo`. Arrancar costaba dos peticiones;
rotar sumaba dos más; cambiar de pestaña o cerrar una ficha sumaba una, porque
`Catalogo` salía y volvía a entrar en composición. Y `recargar()` no tenía guarda: dos
toques eran dos peticiones reales.

**Qué se hizo.** Queda una sola carga automática, en el `init` del ViewModel. El
ViewModel nace y muere con el proceso, así que "una carga automática por sesión" es
literalmente su ciclo de vida: la rotación no lo recrea, cambiar de pestaña tampoco.

La guarda de concurrencia es:

```kotlin
if (cargando.value) return
cargando.value = true
viewModelScope.launch { ... }
```

El orden importa: la bandera se levanta **antes** del `launch`, no adentro.
`viewModelScope` despacha al hilo principal pero no corre inmediato, así que si el
chequeo estuviera dentro de la corrutina dos toques en el mismo frame pasarían los dos.

El extra `servicio_disponible` ahora se escribe directo en las preferencias del
servicio antes de crear el ViewModel, para que el arranque sin señal no dependa del
orden en que se despacha la corrutina del `init`.

---

## Arreglo de compilación: OkHttp

El proyecto **no compilaba tal como vino**, por una razón ajena a los cuatro pedidos.

`ServicioLocal.kt` importa `MediaType.Companion.toMediaType` y
`ResponseBody.Companion.toResponseBody`, dos extensiones de Kotlin que **solo existen
en OkHttp 4**. Pero `build.gradle.kts` nunca declaraba OkHttp: la única vía era la
dependencia transitiva de Retrofit 2.11.0, cuyo POM fija la versión **3.14.9**, donde
esas companion no existen. El error era:

```
e: ServicioLocal.kt:6:26  Unresolved reference: Companion
e: ServicioLocal.kt:7:29  Unresolved reference: Companion
e: ServicioLocal.kt:32:48 Unresolved reference: toResponseBody
e: ServicioLocal.kt:32:82 Unresolved reference: toMediaType
```

**Se agregó** `implementation("com.squareup.okhttp3:okhttp:4.12.0")`. No se reemplazó
`converter-gson`, que `Servicios.kt` usa para `GsonConverterFactory` y cumple otra
función. Efecto colateral a tener presente: OkHttp 4.12.0 arrastra Okio 3.x en lugar
de 1.x.

No se agregó ninguna otra dependencia ni se cambió ninguna versión de Gradle, Kotlin
o Compose.

---

## Qué NO se tocó, a propósito

- **`lab/ServicioLocal.kt`** es el banco de pruebas, no código con errores. El
  `Thread.sleep` del interceptor y el contador que vuelve a cero al morir el proceso
  son parte de la consigna.
- **`legacy/AgendaImpresa.kt`** es código muerto (`filasParaImprimir` no se usa en
  ningún lado). Está fuera de los cuatro pedidos.
- **El esquema de Room** sigue en `version = 1` y `exportSchema = false`. Ningún
  cambio obliga a una migración, así que las instalaciones existentes conservan sus
  datos.

---

## Verificación en emulador

Probado sobre un Pixel_9 (API 37), compilando con JDK 21 y Gradle 8.7.

| Caso | Resultado |
|---|---|
| 44 · Arranque limpio | 1 petición (antes eran 2) |
| 44 · Rotar varias veces | sigue en 1 |
| 44 · Abrir ficha y volver | sigue en 1 |
| 44 · Cambiar de pestaña | sigue en 1 |
| 44 · Dos toques a Actualizar | suma exactamente 1 |
| 44 · Un toque posterior | vuelve a funcionar |
| 42 · v1 → v2 | la 11 conserva la marca y muestra «Compose en producción avanzada» / «Sala Puerto» |
| 42 · v2 → v3 | la 11 sale del catálogo y de Mi agenda, entra la 90, la otra marca se conserva |
| 42 · v3 → v1 | la 11 vuelve con su título y sala originales, y **sin marcar** |
| 41 · Falla con edición previa | la lista queda y avisa datos guardados |
| 41 · Falla sin descarga previa | ofrece Reintentar |
| 41 · v4 exitosa | «Sin charlas programadas», sin caída |
| 41 · Reabrir sin señal tras v4 | sigue mostrando «Sin charlas programadas» |
| 43 · Día 2 + Datos y rotar | quedan los dos resultados de Datos |

**Pendiente de verificar:** la restauración tras muerte del proceso (`adb shell am kill`)
del pedido 43. El mecanismo usado, `SavedStateHandle`, es el estándar para ese caso,
pero no llegó a probarse en el emulador.

---

## Uso de IA

Los arreglos se desarrollaron con asistencia de Claude, usado como revisor y
herramienta de diagnóstico. Los commits lo declaran con una línea `Co-Authored-By`.
El diagnóstico de OkHttp, por ejemplo, se resolvió comparando los jar de las versiones
3.14.9 y 4.12.0 y el POM de Retrofit, no aceptando la primera sugerencia: la propuesta
que circulaba era reemplazar `converter-gson`, lo que habría roto `GsonConverterFactory`.
