
package com.example.watertank

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable


import android.os.Bundle
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.DropdownMenuItem
import java.util.Locale

import com.example.watertank.ui.theme.WATERTANKTheme


private val Azul = Color(0xFF1479D1)
private val Fundo = Color(0xFFF4F7FB)
private val Texto = Color(0xFF152238)
private val Cinza = Color(0xFF718096)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            WATERTANKTheme {
                HomeScreen()
            }
        }
    }
}

data class DadosCaixa(
    val nivelPercentual: Int? = null,
    val volumeAtualLitros: Int? = null,
    val capacidadeLitros: Int = 1000,
    val consumoHojeLitros: Int? = null,
    val sensorConectado: Boolean = false,
    val alerta: String? = null
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
fun HomeScreen() {
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
                0 -> InicioContent()
                1 -> HistoricoContent()
                2 -> AlertasContent()
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
                    }
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
fun AlertasContent() {
    PaginaBase(titulo = "Alertas") {
        Text(
            text = "Tudo tranquilo por aqui!",
            color = Color(0xFF21834A),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Os avisos de nível baixo, nível alto e possíveis vazamentos aparecerão nesta página.",
            color = Cinza
        )
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
    onSalvar: () -> Unit
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

                OutlinedButton(
                    onClick = { editandoTarifa = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cancelar",
                        color = Color.Red
                    )
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
                text = "A configuração da conexão será implementada aqui.",
                color = Cinza
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
