
package com.example.watertank

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

import android.os.Bundle
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.util.UUID
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import java.util.Locale
import androidx.compose.material3.ButtonDefaults

import com.example.watertank.ui.theme.WATERTANKTheme


private val Azul = Color(0xFF1479D1)
private val Fundo = Color(0xFFF4F7FB)
private val Texto = Color(0xFF152238)
private val Cinza = Color(0xFF718096)

class MainActivity : ComponentActivity() {

    private val sppUuid: UUID =
        UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var pararLeitura = false

    private lateinit var permissaoBluetoothLauncher: ActivityResultLauncher<String>

    private var dadosCaixa by mutableStateOf(DadosCaixa())
    private var statusConexao by mutableStateOf("Desconectado")

    private var alertas by mutableStateOf(listOf<Alerta>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissaoBluetoothLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) { concedida ->
                if (concedida) {
                    iniciarConexaoBluetooth()
                } else {
                    statusConexao = "Permissão Bluetooth negada"
                }
            }

        setContent {
            WATERTANKTheme {
                HomeScreen(
                    dados = dadosCaixa,
                    alertas = alertas,
                    statusConexao = statusConexao,
                    onConectar = { solicitarConexaoBluetooth() },
                    onDesconectar = { desconectarBluetooth() },
                    onLimparAlertasResolvidos = {
                        alertas = alertas.filter { it.ativo }
                    }
                )
            }
        }
        solicitarConexaoBluetooth()
    }

    private fun solicitarConexaoBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            permissaoBluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            iniciarConexaoBluetooth()
        }
    }


    private fun horarioAtual(): String {
        return java.text.SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            java.util.Locale("pt", "BR")
        ).format(java.util.Date())
    }

    private fun enviarSincronizacaoHorario(socket: BluetoothSocket) {
        val formato = java.text.SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.US
        )

        val horario = formato.format(java.util.Date())
        val mensagem = "SYNC;$horario\n"

        socket.outputStream.write(
            mensagem.toByteArray(Charsets.UTF_8)
        )
        socket.outputStream.flush()
    }

    private fun atualizarAlerta(
        chave: String,
        disparou: Boolean?,
        titulo: String,
        descricao: String,
        gravidade: String
    ) {
        // Sem leitura válida, não altera o estado do alerta.
        if (disparou == null) return

        val indice = alertas.indexOfFirst {
            it.chave == chave && it.ativo
        }

        if (disparou && indice == -1) {

            // Cria um novo alerta apenas se não existir outro ativo.
            val novoAlerta = Alerta(
                chave = chave,
                titulo = titulo,
                descricao = descricao,
                gravidade = gravidade,
                horario = horarioAtual()
            )

            alertas = (listOf(novoAlerta) + alertas).take(30)

        } else if (!disparou && indice != -1) {

            // Marca o alerta anterior como resolvido.
            val listaAtualizada = alertas.toMutableList()

            listaAtualizada[indice] = listaAtualizada[indice].copy(
                ativo = false,
                descricao = "Condição normalizada."
            )

            alertas = listaAtualizada
        }
    }

    private fun avaliarAlertas(dados: DadosCaixa) {

        // Temperatura acima de 35 °C
        atualizarAlerta(
            chave = "temperatura",
            disparou = dados.temperatura?.let { it > 30.0 },
            titulo = "Água muito quente",
            descricao = "Temperatura acima do limite de 35 °C.",
            gravidade = "Alta"
        )

        // Nível abaixo de 20%
        atualizarAlerta(
            chave = "nivel",
            disparou = dados.nivelPercentual?.let { it < 20 },
            titulo = "Nível baixo de água",
            descricao = "O nível da caixa está abaixo de 20%.",
            gravidade = "Média"
        )

        // pH fora da faixa de 6,5 até 8,5
        atualizarAlerta(
            chave = "ph",
            disparou = dados.ph?.let {
                it < 6.5 || it > 8.5
            },
            titulo = "pH fora da faixa recomendada",
            descricao = "O pH da água está fora da faixa de 6,5 a 8,5.",
            gravidade = "Média"
        )
    }

    @Suppress("MissingPermission")
    private fun iniciarConexaoBluetooth() {
        if (bluetoothSocket?.isConnected == true) {
            statusConexao = "Conectado"
            return
        }

        statusConexao = "Procurando WaterTank_ESP32..."
        pararLeitura = false

        Thread {
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                    ?: throw IOException("Este aparelho não possui Bluetooth.")

                if (!adapter.isEnabled) {
                    throw IOException("Ative o Bluetooth do celular.")
                }

                val dispositivo: BluetoothDevice = adapter.bondedDevices
                    .firstOrNull { it.name == "WaterTank_ESP32" }
                    ?: throw IOException("Dispositivo não pareado. Pareie WaterTank_ESP32 nas configurações do Android.")

                val socket = dispositivo.createRfcommSocketToServiceRecord(sppUuid)
                bluetoothSocket = socket
                socket.connect()
                enviarSincronizacaoHorario(socket)

                runOnUiThread {
                    statusConexao = "Conectado a WaterTank_ESP32"
                    dadosCaixa = dadosCaixa.copy(sensorConectado = true)
                }

                val leitor = BufferedReader(InputStreamReader(socket.inputStream))
                while (!pararLeitura) {
                    val linha = leitor.readLine() ?: break
                    processarLinhaBluetooth(linha)
                }

                if (!pararLeitura) {
                    runOnUiThread {
                        statusConexao = "Conexão encerrada"
                        dadosCaixa = dadosCaixa.copy(sensorConectado = false)
                    }
                }
            } catch (e: SecurityException) {
                if (!pararLeitura) runOnUiThread {
                    statusConexao = "Sem permissão para acessar o Bluetooth"
                    dadosCaixa = dadosCaixa.copy(sensorConectado = false)
                }
            } catch (e: Exception) {
                if (!pararLeitura) runOnUiThread {
                    statusConexao = e.message ?: "Falha na conexão Bluetooth"
                    dadosCaixa = dadosCaixa.copy(sensorConectado = false)
                }
            } finally {
                try { bluetoothSocket?.close() } catch (_: Exception) {}
                bluetoothSocket = null
            }
        }.start()
    }

    private fun processarLinhaBluetooth(linha: String) {

        try {
            val json = JSONObject(linha)

            val ph = json.optNullableDouble("ph")
            val tds = json.optNullableDouble("tds")
            val temperatura = json.optNullableDouble("temperatura")
            val vazao = json.optNullableDouble("vazao")

            val nivelPercentual = json.optNullableDouble("nivel_percentual")
                ?.toInt()
                ?.coerceIn(0, 100)

            val falhas = if (json.has("falhas") && !json.isNull("falhas"))
                json.optInt("falhas") else null

            val dataHora = if (json.has("data_hora") && !json.isNull("data_hora"))
                json.optString("data_hora") else null

            val rtcValido = if (json.has("rtc_valido") && !json.isNull("rtc_valido")) {
                json.optBoolean("rtc_valido")
            } else {
                null
            }

            runOnUiThread {

                val novosDados = dadosCaixa.copy(
                    sensorConectado = true,
                    nivelPercentual = nivelPercentual,
                    ph = ph,
                    tds = tds,
                    temperatura = temperatura,
                    vazao = vazao,
                    falhas = falhas,
                    dataHora = dataHora,
                    rtcValido = rtcValido
                )

                dadosCaixa = novosDados

                // Avaliação automática dos alertas
                avaliarAlertas(novosDados)

                statusConexao = "Conectado a WaterTank_ESP32"
            }

        } catch (_: Exception) {
            // Ignora linhas que não sejam um JSON válido.
        }
    }


    private fun desconectarBluetooth() {
        pararLeitura = true
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {
        }
        bluetoothSocket = null
        dadosCaixa = dadosCaixa.copy(sensorConectado = false)
        statusConexao = "Desconectado"
    }

    override fun onDestroy() {
        desconectarBluetooth()
        super.onDestroy()
    }
}

private fun JSONObject.optNullableDouble(chave: String): Double? {
    if (!has(chave) || isNull(chave)) return null
    val valor = optDouble(chave, Double.NaN)
    return valor.takeIf { it.isFinite() }
}

data class DadosCaixa(
    val nivelPercentual: Int? = null,
    val volumeAtualLitros: Int? = null,
    val capacidadeLitros: Int = 1000,
    val consumoHojeLitros: Int? = null,
    val sensorConectado: Boolean = false,
    val alerta: String? = null,
    val ph: Double? = null,
    val tds: Double? = null,
    val temperatura: Double? = null,
    val vazao: Double? = null,
    val falhas: Int? = null,
    val dataHora: String? = null,
    val rtcValido: Boolean? = null
)


data class Alerta(
    val chave: String,
    val titulo: String,
    val descricao: String,
    val gravidade: String,
    val horario: String,
    val ativo: Boolean = true
)


@Composable
fun InicioContent(
    dados: DadosCaixa = DadosCaixa()
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Fundo
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {

            // Cabeçalho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "WATERTANK",
                    color = Azul,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Olá!",
                color = Texto,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Veja como está sua caixa d'água.",
                color = Cinza,
                fontSize = 14.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Nível da caixa
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                elevation = CardDefaults.cardElevation(3.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "NÍVEL DA CAIXA",
                        color = Cinza,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = dados.nivelPercentual?.let { "$it%" } ?: "--",
                        color = Azul,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold
                    )

                    LinearProgressIndicator(
                        progress = {
                            (dados.nivelPercentual ?: 0)
                                .coerceIn(0, 100) / 100f
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp),
                        color = Azul,
                        trackColor = Color(0xFFE3EDF7)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (dados.volumeAtualLitros != null) {
                            "${dados.volumeAtualLitros} de ${dados.capacidadeLitros} litros"
                        } else {
                            "Aguardando leitura do sensor..."
                        },
                        color = Cinza,
                        fontSize = 14.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = dados.alerta ?: "Aguardando informações...",
                        color = if (dados.alerta != null) {
                            Color(0xFFD97706)
                        } else {
                            Cinza
                        },
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFFEFF4F8),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Resumo do consumo",
                color = Texto,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoCard(
                    titulo = "💧 Consumo hoje",
                    valor = dados.consumoHojeLitros?.let {
                        "$it L"
                    } ?: "--",
                    modifier = Modifier.weight(1f)
                )

                InfoCard(
                    titulo = "📶 Sensor",
                    valor = if (dados.sensorConectado) {
                        "Conectado"
                    } else {
                        "Desconectado"
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Leituras dos sensores",
                        color = Texto,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("pH: ${dados.ph?.let { String.format(Locale.US, "%.2f", it) } ?: "--"}", color = Texto)
                    Text("TDS: ${dados.tds?.let { String.format(Locale.US, "%.2f ppm", it) } ?: "--"}", color = Texto)
                    Text("Temperatura: ${dados.temperatura?.let { String.format(Locale.US, "%.2f °C", it) } ?: "--"}", color = Texto)
                    Text("Vazão: ${dados.vazao?.let { String.format(Locale.US, "%.2f L/min", it) } ?: "--"}", color = Texto)
                    Text("Falhas: ${dados.falhas?.toString() ?: "--"}", color = Texto)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = dados.dataHora?.let { "Atualizado em: $it" } ?: "Aguardando data e hora do ESP32...",
                        color = Cinza,
                        fontSize = 12.sp
                    )
                    if (dados.rtcValido == false) {
                        Text("Atenção: relógio RTC inválido.", color = Color(0xFFD97706), fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Alertas recentes",
                color = Texto,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = dados.alerta
                        ?: "Nenhum alerta recebido.",
                    color = Cinza,
                    modifier = Modifier.padding(18.dp)
                )
            }
        }
    }
}

@Composable
fun InfoCard(
    titulo: String,
    valor: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = titulo,
                color = Cinza,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = valor,
                color = Texto,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


@Composable
fun HomeScreen(
    dados: DadosCaixa,
    statusConexao: String,
    alertas: List<Alerta>,
    onConectar: () -> Unit,
    onDesconectar: () -> Unit,
    onLimparAlertasResolvidos: () -> Unit
) {
    var paginaSelecionada by rememberSaveable {
        mutableIntStateOf(0)
    }
    var estadoSelecionado by rememberSaveable {
        mutableStateOf("CE")
    }

    var tarifaReferencia by rememberSaveable {
        mutableStateOf("")
    }

    var configuracaoSalva by rememberSaveable {
        mutableStateOf(false)
    }
    var capacidadeLitros by rememberSaveable {
        mutableIntStateOf(1000)
    }
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Azul
            ) {
                val paginas = listOf(
                    "Início",
                    "Histórico",
                    "Alertas",
                    "Ajustes"
                )

                val simbolos = listOf("⌂", "▥", "(", "⚙")

                paginas.forEachIndexed { indice, titulo ->
                    NavigationBarItem(
                        selected = paginaSelecionada == indice,
                        onClick = {
                            paginaSelecionada = indice
                        },
                        icon = {
                            if (indice == 2) {
                                Image(
                                    painter = painterResource(id = R.drawable.icone_alerta),
                                    contentDescription = "Alertas",
                                    modifier = Modifier.size(23.dp),
                                    contentScale = ContentScale.Fit,
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            } else {
                                Text(
                                    text = simbolos[indice],
                                    fontSize = 22.sp,
                                    modifier = Modifier.size(24.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        },
                        label = {
                            Text(titulo)
                        },
                        alwaysShowLabel = true
                    )
                }
            }
        },
        containerColor = Fundo
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (paginaSelecionada) {
                0 -> InicioContent(dados = dados.copy(capacidadeLitros = capacidadeLitros))
                1 -> HistoricoContent()
                2 -> AlertasContent(
                    alertas = alertas,
                    onLimparAlertasResolvidos = onLimparAlertasResolvidos
                )
                3 -> ConfiguracoesContent(
                    estadoSelecionado = estadoSelecionado,
                    onEstadoSelecionado = {
                        estadoSelecionado = it
                        configuracaoSalva = false
                    },
                    capacidadeLitros = capacidadeLitros,
                    onCapacidadeChange = {
                        capacidadeLitros = it
                        configuracaoSalva = false
                    },
                    tarifaReferencia = tarifaReferencia,
                    onTarifaChange = {
                        tarifaReferencia = it
                        configuracaoSalva = false
                    },
                    configuracaoSalva = configuracaoSalva,
                    onSalvar = {
                        configuracaoSalva = true
                    },
                    statusConexao = statusConexao,
                    onConectar = onConectar,
                    onDesconectar = onDesconectar
                )
            }
        }
    }
}

@Composable
fun HistoricoContent() {
    PaginaBase(titulo = "Histórico") {
        Text(
            text = "Consumo de água",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = Texto
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Aqui ficará o histórico diário, semanal e mensal.",
            color = Cinza
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text("Hoje                 125 litros", color = Texto)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Ontem               140 litros", color = Texto)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Anteontem         118 litros", color = Texto)
    }
}


@Composable
fun AlertasContent(
    alertas: List<Alerta>,
    onLimparAlertasResolvidos: () -> Unit
) {
    val existemResolvidos = alertas.any { !it.ativo }

    PaginaBase(titulo = "Alertas") {

        Button(
            onClick = onLimparAlertasResolvidos,
            enabled = existemResolvidos,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Red,
                contentColor = Color.White
            )
        ) {

            Spacer(modifier = Modifier.width(8.dp))

            Text("Limpar resolvidos")
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (alertas.isEmpty()) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "✓",
                        fontSize = 36.sp,
                        color = Color(0xFF21834A)
                    )

                    Text(
                        text = "Tudo tranquilo por aqui!",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF21834A)
                    )

                    Text(
                        text = "Nenhum alerta registrado.",
                        color = Cinza,
                        fontSize = 14.sp
                    )
                }
            }

        } else {

            Text(
                text = "Alertas recentes (${alertas.size})",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Texto
            )

            Spacer(modifier = Modifier.height(12.dp))

            alertas.forEach { alerta ->

                val corAlerta = when (alerta.gravidade) {
                    "Alta" -> Color(0xFFD32F2F)
                    "Média" -> Color(0xFFE69A16)
                    else -> Cinza
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color.White
                    ),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = alerta.titulo,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Texto,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = if (alerta.ativo) "ATIVO" else "RESOLVIDO",
                                color = if (alerta.ativo)
                                    corAlerta
                                else
                                    Color(0xFF21834A),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = alerta.descricao,
                            color = Cinza,
                            fontSize = 14.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Gravidade: ${alerta.gravidade}",
                                color = corAlerta,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = alerta.horario,
                                color = Cinza,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfiguracoesContent(
    estadoSelecionado: String,
    onEstadoSelecionado: (String) -> Unit,
    capacidadeLitros: Int,
    onCapacidadeChange: (Int) -> Unit,
    tarifaReferencia: String,
    onTarifaChange: (String) -> Unit,
    configuracaoSalva: Boolean,
    onSalvar: () -> Unit,
    statusConexao: String,
    onConectar: () -> Unit,
    onDesconectar: () -> Unit
) {

    var menuAberto by rememberSaveable { mutableStateOf(false) }
    var editandoTarifa by rememberSaveable { mutableStateOf(false) }
    var tarifaEmEdicao by rememberSaveable { mutableStateOf("") }

    PaginaBase(titulo = "Configurações") {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Caixa d'água",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Texto
            )

            Spacer(modifier = Modifier.height(8.dp))

            val opcoesCapacidade = listOf(500, 1000, 1500, 2000, 3000, 5000)

            Text(
                text = "Capacidade",
                fontWeight = FontWeight.SemiBold,
                color = Texto
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = menuAberto,
                onExpandedChange = { menuAberto = it }
            ) {
                OutlinedTextField(
                    value = String.format(Locale("pt", "BR"), "%,d litros", capacidadeLitros),
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(color = Color.Black),
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuAberto)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = menuAberto,
                    onDismissRequest = { menuAberto = false }
                ) {
                    opcoesCapacidade.forEach { opcao ->
                        DropdownMenuItem(
                            text = {
                                Text(String.format(Locale("pt", "BR"), "%,d litros", opcao))
                            },
                            onClick = {
                                onCapacidadeChange(opcao)
                                menuAberto = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

            Text(
                text = "Tarifa de água",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Texto
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!editandoTarifa) {
                // Modo visualização
                Text(
                    text = if (tarifaReferencia.isBlank()) {
                        "Tarifa atual: não definida"
                    } else {
                        "Tarifa atual: R$ $tarifaReferencia/m³"
                    },
                    fontWeight = FontWeight.SemiBold,
                    color = Texto
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        tarifaEmEdicao = tarifaReferencia
                        editandoTarifa = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (tarifaReferencia.isBlank()) "Definir tarifa" else "Alterar tarifa")
                }

                if (configuracaoSalva) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "✓ Configuração salva.",
                        color = Color(0xFF21834A),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                // Modo edição
                Text(
                    text = "Nova tarifa de referência (R$/m³)",
                    fontWeight = FontWeight.SemiBold,
                    color = Texto
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = tarifaEmEdicao,
                    onValueChange = { novoValor ->
                        if (
                            novoValor.isEmpty() ||
                            novoValor.matches(Regex("""\d{0,5}([,.]\d{0,2})?"""))
                        ) {
                            tarifaEmEdicao = novoValor
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Color.Black),
                    placeholder = { Text("Ex.: 5,50", color = Cinza) },
                    suffix = { Text("R$/m³", color = Cinza) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Informe o valor da tarifa de água por metro cúbico. " +
                            "Este valor não é uma tarifa oficial automática.",
                    color = Cinza,
                    fontSize = 12.sp
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        onTarifaChange(tarifaEmEdicao)
                        onSalvar()
                        editandoTarifa = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = tarifaEmEdicao
                        .replace(',', '.')
                        .toDoubleOrNull()
                        ?.let { it > 0.0 } == true
                ) {
                    Text("Salvar configuração")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { editandoTarifa = false },
                    modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                )
                ) {
                    Text("Cancelar")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 20.dp))

            Text(
                text = "Conexão com ESP32",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Texto
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Status: $statusConexao",
                color = if (statusConexao.startsWith("Conectado")) Color(0xFF21834A) else Cinza,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (statusConexao.startsWith("Conectado")) {
                Button(
                    onClick = onDesconectar,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    Text("Desconectar")
                }
            } else {
                Button(
                    onClick = onConectar,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Conectar ao ESP32")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "O ESP32 precisa estar pareado com o celular como WaterTank_ESP32. Feche o Serial Bluetooth Terminal antes de conectar pelo WATERTANK.",
                color = Cinza,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun PaginaBase(
    titulo: String,
    conteudo: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Fundo)
            .padding(20.dp)
    ) {
        Text(
            text = "WATERTANK",
            color = Azul,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = titulo,
            color = Texto,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        conteudo()
    }
}
