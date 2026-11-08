package org.michimusic.mobile.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.koin.compose.koinInject
import org.michimusic.data.repository.SafMediaRepository
import org.michimusic.mobile.R
import org.michimusic.mobile.ui.theme.InterFontFamily
import org.michimusic.mobile.ui.theme.MichiShapes
import org.michimusic.mobile.ui.theme.PrimaryPinkContainer
import org.michimusic.mobile.ui.theme.PureWhite
import org.michimusic.mobile.ui.theme.SurfaceContainerLow
import org.michimusic.mobile.ui.theme.SurfaceObsidian
import org.michimusic.mobile.ui.theme.TextSecondary

/** First-use flow; no server pairing, audio authority changes or automatic DAC claims. */
@Composable
fun FirstRunWizard(
    initial: OnboardingSnapshot,
    replay: Boolean,
    saveDraft: suspend (OnboardingSnapshot) -> Boolean,
    finish: suspend (OnboardingSnapshot) -> Boolean,
    onCompleted: (OnboardingDestination) -> Unit,
    onDismissReplay: () -> Unit,
    safRepository: SafMediaRepository = koinInject(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var step by rememberSaveable { mutableIntStateOf(if (replay) 0 else initial.step.coerceIn(0, 4)) }
    var library by rememberSaveable { mutableStateOf(initial.library) }
    var discoverLater by rememberSaveable { mutableStateOf(initial.discoverLater) }
    var linkLater by rememberSaveable { mutableStateOf(initial.linkLater) }
    var audio by rememberSaveable { mutableStateOf(initial.audio) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var imported by rememberSaveable { mutableIntStateOf(0) }
    var importFailed by rememberSaveable { mutableIntStateOf(0) }
    var mediaPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    // Android permission status may change in Settings while the Activity is paused.
    // Re-check on every resume; never launch the permission dialog automatically.
    DisposableEffect(lifecycleOwner, permission, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mediaPermissionGranted = ContextCompat.checkSelfPermission(
                    context, permission,
                ) == PackageManager.PERMISSION_GRANTED
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { mediaPermissionGranted = it }
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                error = null
                importFailed = 0
                try {
                    for (uri in uris) {
                        try {
                            if (safRepository.importAudioUri(uri) != null) imported++
                            else importFailed++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            importFailed++
                        }
                    }
                    if (importFailed > 0) {
                        error = "${importFailed} archivo(s) no pudieron importarse. Puedes reintentarlo desde Ajustes."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "No fue posible importar los archivos. Reintenta desde Ajustes."
                } finally {
                    busy = false
                }
            }
        }
    }

    fun snapshot(atStep: Int) = OnboardingSnapshot(
        completed = initial.completed,
        step = atStep,
        library = library,
        discoverLater = discoverLater,
        linkLater = linkLater,
        audio = audio,
    )
    fun move(next: Int, skipEcosystem: Boolean = false) {
        if (busy) return
        // Capture the new state atomically: otherwise an immediate recomposition may
        // persist the previous toggles after tapping "Omitir por ahora".
        val draft = snapshot(next).let {
            if (skipEcosystem) it.copy(discoverLater = false, linkLater = false) else it
        }
        scope.launch {
            busy = true
            error = null
            try {
                if (saveDraft(draft)) {
                    if (skipEcosystem) {
                        discoverLater = false
                        linkLater = false
                    }
                    step = next
                } else error = "No se pudo guardar la configuración. Inténtalo nuevamente."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "No se pudo guardar la configuración. Inténtalo nuevamente."
            } finally {
                busy = false
            }
        }
    }
    fun completeWizard() {
        if (busy) return
        val committed = snapshot(4)
        scope.launch {
            busy = true
            error = null
            try {
                if (finish(committed)) onCompleted(OnboardingDestination.HOME)
                else error = "No se pudo finalizar. Inténtalo nuevamente."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "No se pudo finalizar. Inténtalo nuevamente."
            } finally {
                busy = false
            }
        }
    }
    BackHandler(enabled = step > 0 || replay) {
        if (step > 0) move(step - 1) else if (replay) onDismissReplay()
    }

    val titles = listOf(
        "Tu música. Tu mundo.",
        "¿Dónde está tu música?",
        "Más juntos. Perfecto por separado.",
        "El sonido a tu medida.",
        "¡Todo preparado!",
    )
    Surface(color = SurfaceObsidian, modifier = Modifier.fillMaxSize().testTag("first_run_wizard")) {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painter = painterResource(R.drawable.wizard_brand_official), contentDescription = null, modifier = Modifier.size(30.dp))
                Column(modifier = Modifier.padding(start = 9.dp)) {
                    Text("michi.", color = PureWhite, fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily, fontSize = 18.sp, lineHeight = 19.sp)
                    Text("MUSIC MOBILE", color = TextSecondary,
                        fontFamily = InterFontFamily, fontSize = 9.sp, lineHeight = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("${step + 1} / 5", color = TextSecondary, fontSize = 12.sp,
                    modifier = Modifier.testTag("wizard_step_counter"))
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                repeat(5) { index ->
                    Box(
                        Modifier.weight(1f).height(4.dp)
                            .background(if (index <= step) PrimaryPinkContainer else SurfaceContainerLow, CircleShape),
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // The same sequence and image-to-step mapping as the approved design.
                WizardHero(step)
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("PASO ${step + 1} · ${listOf("BIENVENIDA", "BIBLIOTECA", "ECOSISTEMA", "AUDIO", "FINALIZAR")[step]}",
                        color = PrimaryPinkContainer, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        fontFamily = InterFontFamily, letterSpacing = 0.8.sp)
                    if (step == 2) {
                        Surface(shape = RoundedCornerShape(10.dp), color = Color(0x403E291B),
                            border = BorderStroke(1.dp, Color(0xFF885E44))) {
                            Text("OPCIONAL", color = Color(0xFFFFC8A2), fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                        }
                    }
                }
                Text(titles[step], color = PureWhite, fontFamily = InterFontFamily,
                    fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp)
                when (step) {
                    0 -> {
                        Description("Descubre una experiencia musical hecha para tu teléfono. Tu biblioteca, tu sonido y tu estilo, sin complicaciones.")
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            WelcomeBenefit("Escucha sin conexión", "Disfruta tus archivos locales.",
                                R.drawable.wizard_fluent_library, Modifier.weight(1f))
                            WelcomeBenefit("Sin equipos extra", "Todo lo demás es opcional.",
                                R.drawable.wizard_fluent_phone, Modifier.weight(1f))
                        }
                    }
                    1 -> {
                        Description("Elige cómo quieres incorporar música. También puedes hacerlo después desde Ajustes.")
                        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Choice("Música de mi teléfono", "Buscar audio local mediante Android.",
                                iconRes = R.drawable.wizard_fluent_phone, selected = library == LibraryChoice.PHONE, onClick = { library = LibraryChoice.PHONE })
                            Choice("Importar archivos", "Selecciona canciones con el explorador de Android.",
                                iconRes = R.drawable.wizard_fluent_document_folder, selected = library == LibraryChoice.IMPORT_FILES, onClick = { library = LibraryChoice.IMPORT_FILES })
                            Choice("Más tarde", "Entrar sin configurar una biblioteca todavía.",
                                iconRes = R.drawable.wizard_fluent_history, selected = library == LibraryChoice.LATER, onClick = { library = LibraryChoice.LATER })
                        }
                        if (library == LibraryChoice.PHONE) {
                            if (mediaPermissionGranted) Description("Acceso a música local concedido.")
                            else OutlinedButton(onClick = { permissionLauncher.launch(permission) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text("Dar acceso a mi música")
                            }
                        } else if (library == LibraryChoice.IMPORT_FILES) {
                            OutlinedButton(
                                onClick = { filesLauncher.launch(arrayOf("audio/*", "application/ogg")) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            ) { Icon(Icons.Default.AudioFile, null); Text("  Seleccionar archivos") }
                            if (imported > 0) Description("$imported archivo(s) importado(s) en esta sesión.")
                            else Description("Aún no has seleccionado archivos en esta sesión; puedes hacerlo después.")
                        }
                    }
                    2 -> {
                        Description("Michi funciona por sí solo. No necesitas servidor, cuenta ni otros equipos para comenzar.")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(painterResource(R.drawable.wizard_fluent_phone_laptop), null, Modifier.size(34.dp))
                            Description("Sin otros dispositivos: puedes omitir este paso.")
                        }
                        ToggleCard("Explorar dispositivos después", "Mostrar un acceso a Devices en Ajustes, sin buscar ahora.", discoverLater) {
                            discoverLater = it
                        }
                        ToggleCard("Revisar Michi Link después", "Mostrar un acceso a identidad en Ajustes, sin crear una cuenta.", linkLater) {
                            linkLater = it
                        }
                        Description("Este paso es opcional. Ningún servidor, búsqueda ni cuenta se activará por seleccionar estas opciones.")
                    }
                    3 -> {
                        Description("Android gestiona la salida de audio de forma predeterminada. Puedes ajustar un DAC USB desde Salidas de audio.")
                        Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Choice("Usar la salida de Android", "Altavoces y audífonos seleccionados en el sistema.",
                                iconRes = R.drawable.wizard_fluent_headphones, selected = audio == AudioChoice.ANDROID, onClick = { audio = AudioChoice.ANDROID })
                            Choice("Configurar un DAC USB más adelante", "Muestra un acceso en Ajustes; no cambia la ruta de audio.",
                                iconRes = R.drawable.wizard_audio_waveform, selected = audio == AudioChoice.USB_REMINDER, onClick = { audio = AudioChoice.USB_REMINDER })
                        }
                        Description("Signal Truth mostrará únicamente capacidades y formatos verificados.")
                    }
                    else -> {
                        Description("Puedes ajustar estas opciones más adelante. Tu música te espera.")
                        Surface(shape = MichiShapes.md, color = SurfaceContainerLow,
                            border = BorderStroke(1.dp, Color(0xFF424B58))) {
                            Column(modifier = Modifier.padding(horizontal = 14.dp)) {
                                SummaryRow("Biblioteca", R.drawable.wizard_fluent_library, when (library) {
                                    LibraryChoice.PHONE -> if (mediaPermissionGranted) "Permiso concedido; se revisará la biblioteca" else "Permiso de audio pendiente"
                                    LibraryChoice.IMPORT_FILES -> if (imported > 0) "$imported archivos importados en esta sesión"
                                        else "Importar archivos cuando quieras"
                                    LibraryChoice.LATER -> "Configurar después"
                                }, status = when (library) {
                                    LibraryChoice.PHONE -> if (mediaPermissionGranted) "LISTO" else "PENDIENTE"
                                    LibraryChoice.IMPORT_FILES -> if (imported > 0) "LISTO" else "OPCIONAL"
                                    LibraryChoice.LATER -> "OPCIONAL"
                                })
                                SummaryRow("Ecosistema", R.drawable.wizard_fluent_phone_laptop,
                                    if (discoverLater || linkLater) "Accesos disponibles en Ajustes" else "Sin búsqueda iniciada (opcional)",
                                    status = "OPCIONAL")
                                SummaryRow("Audio", R.drawable.wizard_audio_waveform,
                                    if (audio == AudioChoice.ANDROID) "Gestionada por Android" else "DAC USB: configurar después",
                                    status = if (audio == AudioChoice.ANDROID) "LISTO" else "PENDIENTE")
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp).testTag("wizard_error")) }
            if (step == 2) {
                TextButton(
                    onClick = { move(3, skipEcosystem = true) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .testTag("wizard_skip_ecosystem"),
                ) { Text("Omitir por ahora · continuar sin dispositivos", color = PrimaryPinkContainer) }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                if (step > 0 || replay) {
                    OutlinedButton(
                        onClick = { if (step > 0) move(step - 1) else onDismissReplay() },
                        enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("wizard_back"),
                    ) { Text(if (step == 0) "Cerrar" else "Anterior") }
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        if (step < 4) move(step + 1)
                        else completeWizard()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("wizard_next"),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryPinkContainer, contentColor = Color(0xFF2C1018)),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) { Text(if (step == 4) "Finalizar" else "Siguiente", fontWeight = FontWeight.SemiBold) }
            }
            Text("Michi Music Mobile · Tu música es tuya", color = TextSecondary,
                fontSize = 10.sp, modifier = Modifier.align(Alignment.CenterHorizontally)
                    .padding(top = 8.dp))
        }
    }
}

/** The approved photos are local and never synthesized into the official brand icon. */
@Composable
private fun WizardHero(step: Int) {
    val photo = when (step) {
        0 -> R.drawable.wizard_hero_welcome
        1 -> R.drawable.wizard_hero_library
        2 -> R.drawable.wizard_hero_ecosystem
        3 -> R.drawable.wizard_hero_audio
        else -> R.drawable.wizard_hero_finished
    }
    val caption = when (step) {
        0 -> "TU MÚSICA. TU ESPACIO."
        1 -> "EXPLORA TU BIBLIOTECA"
        2 -> "CONECTA CUANDO QUIERAS"
        3 -> "TU SONIDO, A TU MANERA"
        else -> "EMPIEZA A ESCUCHAR"
    }
    Box(modifier = Modifier.fillMaxWidth().height(174.dp)
        .clip(RoundedCornerShape(18.dp))) {
        Image(
            painter = painterResource(photo), contentDescription = null,
            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier.fillMaxWidth().height(76.dp)
                .align(Alignment.BottomCenter)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC090C12)))),
        )
        Surface(
            shape = RoundedCornerShape(32.dp), color = Color(0xC7090C12),
            border = BorderStroke(1.dp, Color(0x665A647B)),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Text("FELINE HI-FI ATELIER", color = PureWhite, letterSpacing = 1.sp,
                fontSize = 9.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
        }
        Text(caption, color = PureWhite, fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.BottomStart).padding(14.dp))
    }
}

@Composable
private fun Description(text: String) {
    Text(text, color = TextSecondary, fontFamily = InterFontFamily, fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun Choice(title: String, subtitle: String, iconRes: Int, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = MichiShapes.md,
        color = if (selected) PrimaryPinkContainer.copy(alpha = 0.12f) else SurfaceContainerLow,
        border = BorderStroke(1.dp, if (selected) PrimaryPinkContainer else Color(0xFF424B58)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Image(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(34.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(title, color = PureWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp, lineHeight = 17.sp)
            }
            RadioButton(
                selected = selected, onClick = null,
                colors = RadioButtonDefaults.colors(selectedColor = PrimaryPinkContainer),
            )
        }
    }
}

@Composable
private fun ToggleCard(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = SurfaceContainerLow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChecked)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = PureWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, color = TextSecondary, fontSize = 12.sp)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
private fun WelcomeBenefit(title: String, subtitle: String, iconRes: Int, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.heightIn(min = 106.dp),
        shape = MichiShapes.md, color = SurfaceContainerLow,
        border = BorderStroke(1.dp, Color(0xFF424B58))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Image(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(30.dp))
            Text(title, color = PureWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = InterFontFamily)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp, lineHeight = 14.sp,
                fontFamily = InterFontFamily)
        }
    }
}

@Composable
private fun SummaryRow(title: String, iconRes: Int, subtitle: String, status: String) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(32.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = PureWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = InterFontFamily)
            Text(subtitle, color = TextSecondary, fontSize = 12.sp,
                fontFamily = InterFontFamily)
        }
        Text(status, color = if (status == "LISTO") Color(0xFFA8E7C7)
            else Color(0xFFFFC7AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}
