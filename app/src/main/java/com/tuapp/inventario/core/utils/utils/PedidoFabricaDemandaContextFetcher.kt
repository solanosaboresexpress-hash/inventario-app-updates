package com.tuapp.inventario.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Clima: Open-Meteo (sin API key).
 * Partidos Primera: API poblica de ESPN `arg.1` (misma liga que muestra Google), sin registro.
 * Respaldo: openfootball/football.json en GitHub si ESPN no responde.
 */
data class DemandaManianaContexto(
    val fechaIso: String,
    val fechaMostrar: String,
    val lineaClima: String,
    val lineasFutbol: List<String>,
    val sugerenciaExtra: String,
    val hayAlertaFuerte: Boolean,
    val weatherCode: Int = -1
)

object PedidoFabricaDemandaContextFetcher {

    private val http = OkHttpClient.Builder()
        .connectTimeout(14, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .build()

    /** Buenos Aires por defecto (se puede ampliar con prefs / geocoding). */
    private const val DEFAULT_LAT = -34.6037
    private const val DEFAULT_LON = -58.3816

    /** Calendario Primera Division Argentina (datos abiertos, sin API key). */
    private const val OPENFOOTBALL_BASE =
        "https://raw.githubusercontent.com/openfootball/football.json/master"

    suspend fun fetch(fechaManianaIso: String): DemandaManianaContexto = withContext(Dispatchers.IO) {
        val clima = fetchOpenMeteoDia(fechaManianaIso)
        val partidos = fetchTodosLosPartidosArgentinos(fechaManianaIso)
        combinar(fechaManianaIso, clima, partidos)
    }

    private fun combinar(
        fechaIso: String,
        clima: ResultadoClimaDia?,
        partidos: List<String>
    ): DemandaManianaContexto {
        val fechaMostrar = try {
            val p = fechaIso.split("-")
            if (p.size == 3) "${p[2]}/${p[1]}" else fechaIso
        } catch (_: Exception) {
            fechaIso
        }

        // Una sola lonea de clima (lo que el usuario quiere ver)
        val lineaClima = if (clima != null) {
            val desc = descripcionClimaWmo(clima.code)
            val tmin = clima.tMin?.roundToInt()
            val tmax = clima.tMax?.roundToInt()
            val tempTxt = when {
                tmin != null && tmax != null -> "${tmin} / ${tmax}"
                tmax != null -> "${tmax}"
                else -> ""
            }
            val lluvia = when {
                clima.probLluvia != null && clima.probLluvia > 0 ->
                    "  prob. lluvia ${clima.probLluvia}%"
                clima.precipMm != null && clima.precipMm > 0 ->
                    "  lluvia ~${String.format(Locale.US, "%.1f", clima.precipMm)} mm"
                else -> ""
            }
            buildString {
                append(emojiClimaWmo(clima.code))
                append(" ")
                append(desc)
                if (tempTxt.isNotBlank()) append(" (").append(tempTxt).append(")")
                append(lluvia)
            }
        } else {
            " No se pudo cargar el clima."
        }

        val lineasFutbol = partidos.map { " $it" }

        val alertaLluvia = clima?.let { c ->
            (c.probLluvia != null && c.probLluvia >= 55) ||
                (c.precipMm != null && c.precipMm >= 5) ||
                c.code in setOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99)
        } ?: false

        val alertaTemperatura = clima?.let { c ->
            val mx = c.tMax
            val mn = c.tMin
            (mx != null && mx >= 35) || (mn != null && mn <= 8)
        } ?: false

        val hayPartido = lineasFutbol.isNotEmpty()
        val hayAlertaFuerte = alertaLluvia || alertaTemperatura || hayPartido

        return DemandaManianaContexto(
            fechaIso = fechaIso,
            fechaMostrar = fechaMostrar,
            lineaClima = lineaClima,
            lineasFutbol = lineasFutbol,
            sugerenciaExtra = "",
            hayAlertaFuerte = hayAlertaFuerte,
            weatherCode = clima?.code ?: -1
        )
    }

    private data class ResultadoClimaDia(
        val code: Int,
        val tMin: Double?,
        val tMax: Double?,
        val probLluvia: Int?,
        val precipMm: Double?
    )

    private fun fetchOpenMeteoDia(fechaIso: String): ResultadoClimaDia? {
        val url = "https://api.open-meteo.com/v1/forecast?" +
            "latitude=$DEFAULT_LAT&longitude=$DEFAULT_LON" +
            "&daily=weathercode,temperature_2m_max,temperature_2m_min," +
            "precipitation_probability_max,precipitation_sum" +
            "&timezone=America%2FArgentina%2FBuenos_Aires" +
            "&start_date=$fechaIso&end_date=$fechaIso"
        return try {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return null
                r.body?.string() ?: return null
            }
            val root = JSONObject(body)
            val daily = root.getJSONObject("daily")
            val times = daily.optJSONArray("time") ?: return null
            if (times.length() < 1) return null
            val code = daily.getJSONArray("weathercode").getInt(0)
            val tMax = jsonArrayDouble0(daily, "temperature_2m_max")
            val tMin = jsonArrayDouble0(daily, "temperature_2m_min")
            val probArr = daily.optJSONArray("precipitation_probability_max")
            val prob = if (probArr != null && probArr.length() > 0 && !probArr.isNull(0)) {
                probArr.optInt(0, -1).takeIf { it >= 0 }
            } else null
            val precipArr = daily.optJSONArray("precipitation_sum")
            val precip = if (precipArr != null && precipArr.length() > 0 && !precipArr.isNull(0)) {
                precipArr.optDouble(0, Double.NaN).takeIf { !it.isNaN() }
            } else null
            ResultadoClimaDia(code, tMin, tMax, prob, precip)
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonArrayDouble0(daily: JSONObject, key: String): Double? {
        val arr = daily.optJSONArray(key) ?: return null
        if (arr.length() < 1 || arr.isNull(0)) return null
        return try {
            arr.getDouble(0)
        } catch (_: Exception) {
            null
        }
    }

    private fun emojiClimaWmo(code: Int): String = when (code) {
        0 -> ""
        1, 2, 3 -> ""
        45, 48 -> ""
        51, 53, 55 -> ""
        56, 57 -> ""
        61, 63, 65 -> ""
        66, 67 -> ""
        71, 73, 75 -> ""
        77 -> ""
        80, 81, 82 -> ""
        85, 86 -> ""
        95, 96, 99 -> ""
        else -> ""
    }

    private fun descripcionClimaWmo(code: Int): String = when (code) {
        0 -> "Despejado"
        1, 2, 3 -> "Mayormente despejado / nublado"
        45, 48 -> "Niebla"
        51, 53, 55 -> "Llovizna"
        56, 57 -> "Llovizna helada"
        61, 63, 65 -> "Lluvia"
        66, 67 -> "Lluvia helada"
        71, 73, 75 -> "Nieve"
        77 -> "Granizo"
        80, 81, 82 -> "Chubascos"
        85, 86 -> "Chubascos de nieve"
        95 -> "Tormenta"
        96, 99 -> "Tormenta con granizo"
        else -> "Condicion variable ($code)"
    }

    /**
     * Busca TODOS los partidos donde participen clubes argentinos en moltiples competiciones:
     * - Primera Division (ESPN)
     * - Copa Libertadores (ESPN)
     * - Copa Sudamericana (ESPN)
     * - Seleccion Argentina (ESPN)
     * - Otras competiciones internacionales
     */
    private fun fetchTodosLosPartidosArgentinos(fechaIso: String): List<String> {
        val todosLosPartidos = mutableListOf<String>()
        
        // 1. Primera Division Argentina
        val partidosPrimera = fetchPartidosPrimeraArgentina(fechaIso)
        todosLosPartidos.addAll(partidosPrimera)
        
        // 2. Copa Libertadores
        val partidosLibertadores = fetchCopaLibertadores(fechaIso)
        todosLosPartidos.addAll(partidosLibertadores)
        
        // 3. Copa Sudamericana
        val partidosSudamericana = fetchCopaSudamericana(fechaIso)
        todosLosPartidos.addAll(partidosSudamericana)
        
        // 4. Seleccion Argentina
        val partidosSeleccion = fetchSeleccionArgentina(fechaIso)
        todosLosPartidos.addAll(partidosSeleccion)
        
        // 5. Otras competiciones (se pueden agregar mos adelante)
        // val partidosInternacionales = fetchPartidosInternacionales(fechaIso)
        // todosLosPartidos.addAll(partidosInternacionales)
        
        // Eliminar duplicados y ordenar por hora
        return todosLosPartidos
            .distinctBy { it.trim() }
            .sortedBy { linea ->
                // Extraer hora para ordenar
                val regexHora = Regex("""(\d{1,2}:\d{2})""")
                val match = regexHora.find(linea)
                match?.groupValues?.get(0) ?: "99:99"
            }
            .take(15) // Limitar a 15 partidos para no sobrecargar
    }
    
    private fun esClubArgentino(nombre: String): Boolean {
        val n = nombre.lowercase().replace("o", "a").replace("o", "e").replace("o", "i")
            .replace("o", "o").replace("o", "u").trim()
        
        val exactos = setOf(
            "boca juniors", "boca", "boca jrs", "river plate", "river", "racing club", "racing", "independiente", "san lorenzo",
            "estudiantes de la plata", "estudiantes la plata", "estudiantes (lp)", "estudiantes", 
            "gimnasia la plata", "gimnasia", "gimnasia (lp)", "rosario central", "rosario ctal",
            "newell's old boys", "newell's", "newells", "newells old boys",
            "talleres (cordoba)", "talleres (c)", "talleres", "belgrano", "belgrano (cordoba)", "belgrano (c)",
            "instituto", "instituto (cordoba)", "instituto (c)", "lanus", "banfield", "argentinos juniors", "argentinos jrs", "argentinos",
            "velez sarsfield", "velez", "defensa y justicia", "defensa", "godoy cruz", "huracan", "platense",
            "sarmiento (junin)", "sarmiento", "union", "union (santa fe)", "tigre",
            "barracas central", "barracas", "central cordoba (santiago del estero)", "central cordoba", "central cba",
            "atletico tucuman", "atl. tucuman", "atl tucuman", "deportivo riestra", "riestra", "independiente rivadavia",
            "aldosivi", "san martin", "san martin (sj)", "san martin (t)", "patronato", "colon", "arsenal"
        )
        if (n in exactos) return true
        
        if (n.contains("independiente") && !n.contains("valle") && !n.contains("medellin") && !n.contains("petrolero") && !n.contains("santa fe")) return true
        if (n.contains("estudiantes") && !n.contains("merida") && !n.contains("caracas")) return true
        
        return false
    }

    /**
     * Busca partidos de Copa Libertadores
     */
    private fun fetchCopaLibertadores(fechaIso: String): List<String> {
        return try {
            val datesParam = fechaIso.replace("-", "")
            // Endpoint real de Conmebol Libertadores
            val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/conmebol.libertadores/scoreboard?dates=$datesParam"
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                r.body?.string() ?: return emptyList()
            }
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return emptyList()
            val tzAr = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val out = ArrayList<String>()
            
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val comps = ev.optJSONArray("competitions") ?: continue
                if (comps.length() < 1) continue
                val comp = comps.getJSONObject(0)
                
                val competitors = comp.optJSONArray("competitors") ?: continue
                var homeName = ""
                var awayName = ""
                for (j in 0 until competitors.length()) {
                    val c = competitors.optJSONObject(j) ?: continue
                    val ha = c.optString("homeAway", "")
                    val team = c.optJSONObject("team") ?: continue
                    val name = team.optString("shortDisplayName", team.optString("displayName", "?")).trim()
                    when (ha) {
                        "home" -> homeName = name
                        "away" -> awayName = name
                    }
                }
                if (homeName.isEmpty() || awayName.isEmpty()) continue
                
                // FILTRO: Solo agregar si alguno de los dos es argentino
                if (!esClubArgentino(homeName) && !esClubArgentino(awayName)) continue
                
                val isoDate = comp.optString("date", ev.optString("date", ""))
                val hora = formatearHoraArgentina(isoDate, tzAr)
                val linea = if (hora.isNotEmpty()) "$homeName vs $awayName  $hora (Libertadores)" else "$homeName vs $awayName (Libertadores)"
                out.add(linea)
                if (out.size >= 8) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Busca partidos de Copa Sudamericana
     */
    private fun fetchCopaSudamericana(fechaIso: String): List<String> {
        return try {
            val datesParam = fechaIso.replace("-", "")
            // Endpoint real de Conmebol Sudamericana
            val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/conmebol.sudamericana/scoreboard?dates=$datesParam"
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                r.body?.string() ?: return emptyList()
            }
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return emptyList()
            val tzAr = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val out = ArrayList<String>()
            
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val comps = ev.optJSONArray("competitions") ?: continue
                if (comps.length() < 1) continue
                val comp = comps.getJSONObject(0)
                
                val competitors = comp.optJSONArray("competitors") ?: continue
                var homeName = ""
                var awayName = ""
                for (j in 0 until competitors.length()) {
                    val c = competitors.optJSONObject(j) ?: continue
                    val ha = c.optString("homeAway", "")
                    val team = c.optJSONObject("team") ?: continue
                    val name = team.optString("shortDisplayName", team.optString("displayName", "?")).trim()
                    when (ha) {
                        "home" -> homeName = name
                        "away" -> awayName = name
                    }
                }
                if (homeName.isEmpty() || awayName.isEmpty()) continue
                
                // FILTRO: Solo agregar si alguno de los dos es argentino
                if (!esClubArgentino(homeName) && !esClubArgentino(awayName)) continue
                
                val isoDate = comp.optString("date", ev.optString("date", ""))
                val hora = formatearHoraArgentina(isoDate, tzAr)
                val linea = if (hora.isNotEmpty()) "$homeName vs $awayName  $hora (Sudamericana)" else "$homeName vs $awayName (Sudamericana)"
                out.add(linea)
                if (out.size >= 8) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Busca partidos de la Seleccion Argentina
     */
    private fun fetchSeleccionArgentina(fechaIso: String): List<String> {
        return try {
            val datesParam = fechaIso.replace("-", "")
            // Endpoint de Seleccion Argentina
            val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/arg.1/scoreboard?dates=$datesParam"
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                r.body?.string() ?: return emptyList()
            }
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return emptyList()
            val tzAr = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val out = ArrayList<String>()
            
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val comps = ev.optJSONArray("competitions") ?: continue
                if (comps.length() < 1) continue
                val comp = comps.getJSONObject(0)
                
                // Filtrar solo Seleccion Argentina
                val competitionName = comp.optString("name", "").lowercase()
                if (!competitionName.contains("argentina") && !competitionName.contains("seleccion") && !competitionName.contains("national")) {
                    continue
                }
                
                val competitors = comp.optJSONArray("competitors") ?: continue
                var homeName = ""
                var awayName = ""
                for (j in 0 until competitors.length()) {
                    val c = competitors.optJSONObject(j) ?: continue
                    val ha = c.optString("homeAway", "")
                    val team = c.optJSONObject("team") ?: continue
                    val name = team.optString("shortDisplayName", team.optString("displayName", "?")).trim()
                    when (ha) {
                        "home" -> homeName = name
                        "away" -> awayName = name
                    }
                }
                if (homeName.isEmpty() || awayName.isEmpty()) continue
                val isoDate = comp.optString("date", ev.optString("date", ""))
                val hora = formatearHoraArgentina(isoDate, tzAr)
                val linea = if (hora.isNotEmpty()) " $homeName vs $awayName  $hora (Seleccion)" else " $homeName vs $awayName (Seleccion)"
                out.add(linea)
                if (out.size >= 8) break
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * ESPN scoreboard Liga Profesional (arg.1)  calendario alineado con lo que suele mostrar Google.
     * [fechaIso] yyyy-MM-dd  parometro dates=yyyyMMdd
     */
    private fun fetchPartidosPrimeraArgentina(fechaIso: String): List<String> {
        val partidos = mutableListOf<String>()
        
        // 1. Primera Division Argentina (ESPN)
        val partidosPrimera = fetchPartidosPrimeraArgentinaEspn(fechaIso)
        partidos.addAll(partidosPrimera)
        
        // 2. Copa Libertadores (ESPN) - Nueva implementacion
        val partidosLibertadores = fetchCopaLibertadores(fechaIso)
        partidos.addAll(partidosLibertadores)
        
        // 3. OpenFootball como respaldo
        val partidosOpenFootball = fetchPartidosPrimeraArgentinaOpenFootball(fechaIso)
        partidos.addAll(partidosOpenFootball)
        
        // Eliminar duplicados y ordenar por hora
        return partidos
            .distinctBy { it.trim() }
            .sortedBy { linea ->
                // Extraer hora para ordenar
                val regexHora = Regex("""(\d{1,2}:\d{2})""")
                val match = regexHora.find(linea)
                match?.groupValues?.get(0) ?: "99:99"
            }
            .take(15) // Limitar a 15 partidos para no sobrecargar
    }
    

    
    /**
     * ESPN scoreboard Liga Profesional (arg.1)  calendario alineado con lo que suele mostrar Google.
     * [fechaIso] yyyy-MM-dd  parometro dates=yyyyMMdd
     */
    private fun fetchPartidosPrimeraArgentinaEspn(fechaIso: String): List<String> {
        return try {
            val datesParam = fechaIso.replace("-", "")
            val url = "https://site.api.espn.com/apis/site/v2/sports/soccer/arg.1/scoreboard?dates=$datesParam"
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                r.body?.string() ?: return emptyList()
            }
            val root = JSONObject(body)
            val events = root.optJSONArray("events") ?: return emptyList()
            val tzAr = TimeZone.getTimeZone("America/Argentina/Buenos_Aires")
            val out = ArrayList<String>(12)
            for (i in 0 until events.length()) {
                val ev = events.optJSONObject(i) ?: continue
                val comps = ev.optJSONArray("competitions") ?: continue
                if (comps.length() < 1) continue
                val comp = comps.getJSONObject(0)
                val competitors = comp.optJSONArray("competitors") ?: continue
                var homeName = ""
                var awayName = ""
                for (j in 0 until competitors.length()) {
                    val c = competitors.optJSONObject(j) ?: continue
                    val ha = c.optString("homeAway", "")
                    val team = c.optJSONObject("team") ?: continue
                    val name = team.optString("shortDisplayName", team.optString("displayName", "?")).trim()
                    when (ha) {
                        "home" -> homeName = name
                        "away" -> awayName = name
                    }
                }
                if (homeName.isEmpty() || awayName.isEmpty()) continue
                val isoDate = comp.optString("date", ev.optString("date", ""))
                val hora = formatearHoraArgentina(isoDate, tzAr)
                val linea = if (hora.isNotEmpty()) "$homeName vs $awayName  $hora (Primera)" else "$homeName vs $awayName (Primera)"
                out.add(linea)
                if (out.size >= 12) break
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun formatearHoraArgentina(isoUtc: String, tzAr: TimeZone): String {
        if (isoUtc.length < 10) return ""
        return try {
            var s = isoUtc
            if (s.contains(".")) s = s.replace(Regex("\\.\\d+"), "")
            val normalized = if (s.endsWith("Z")) {
                s.dropLast(1) + "+0000"
            } else s

            val fmtOut = SimpleDateFormat("HH:mm", Locale("es", "AR"))
            fmtOut.timeZone = tzAr
            
            val validPatterns = listOf("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mmZ")
            for (pattern in validPatterns) {
               try {
                   val fmtIn = SimpleDateFormat(pattern, Locale.US)
                   val date = fmtIn.parse(normalized)
                   if (date != null) return fmtOut.format(date)
               } catch (_: Exception) {}
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Partidos desde archivos JSON poblicos en GitHub (respaldo).
     */
    private fun fetchPartidosPrimeraArgentinaOpenFootball(fechaIso: String): List<String> {
        val urls = urlsOpenFootballArgentina(fechaIso)
        for (url in urls) {
            val found = extraerPartidosDelJsonOpenFootball(url, fechaIso)
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun urlsOpenFootballArgentina(fechaIso: String): List<String> {
        val y = fechaIso.take(4).toIntOrNull() ?: 2025
        return listOf(
            "$OPENFOOTBALL_BASE/$y/ar.1.json",
            "$OPENFOOTBALL_BASE/${y + 1}/ar.1.json",
            "$OPENFOOTBALL_BASE/${y - 1}/ar.1.json",
            "$OPENFOOTBALL_BASE/2025/ar.1.json"
        ).distinct()
    }

    private fun extraerPartidosDelJsonOpenFootball(url: String, fechaIso: String): List<String> {
        return try {
            val body = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.code == 404 || !r.isSuccessful) return emptyList()
                r.body?.string() ?: return emptyList()
            }
            val root = JSONObject(body)
            val matches = root.optJSONArray("matches") ?: return emptyList()
            filtrarPartidosPorFecha(matches, fechaIso)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun filtrarPartidosPorFecha(matches: JSONArray, fechaIso: String): List<String> {
        val out = ArrayList<String>(8)
        for (i in 0 until matches.length()) {
            val m = matches.optJSONObject(i) ?: continue
            if (m.optString("date", "") != fechaIso) continue
            val t1 = m.optString("team1", "?").trim()
            val t2 = m.optString("team2", "?").trim()
            val time = m.optString("time", "").trim()
            val round = m.optString("round", "").trim()
            val sb = StringBuilder("$t1 vs $t2")
            if (time.isNotEmpty()) sb.append("  ").append(time)
            if (round.isNotEmpty()) sb.append("  ").append(round)
            out.add(sb.toString())
            if (out.size >= 8) break
        }
        return out
    }
}
