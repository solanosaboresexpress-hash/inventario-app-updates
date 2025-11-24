# Comandos Git para Versión 2.1.62

## 📋 Resumen de Cambios
- **Versión**: 2.1.62 (Build 88)
- **Tipo**: Patch Release (Correcciones críticas)
- **Fecha**: 2025-01-24

## 🔧 CORRECCIONES CRÍTICAS

### Previsualización
- ✅ Botones Guardar/Cancelar/Editar ahora siempre visibles
- ✅ ScrollView con altura dinámica (no oculta botones)
- ✅ Diálogo con altura máxima del 80% de pantalla
- ✅ Layout mejorado con weight para ScrollView y altura fija para botones

### Sistema FIFO de Vencimientos
- ✅ Sincronización automática después del descuento FIFO
- ✅ Ajuste automático cuando hay más lotes que stock final
- ✅ Validación mejorada: no crea lotes automáticos (evita fechas incorrectas)
- ✅ Logs detallados para debugging de vencimientos
- ✅ Advertencias claras cuando faltan lotes por cargar manualmente

## 📁 Archivos Modificados

1. `version_info.json` - Actualizado a v2.1.62 (Build 88)
2. `app/build.gradle` - Actualizado versionCode 88 y versionName 2.1.62
3. `app/src/main/java/com/tuapp/inventario/CargaActivity.kt` - Correcciones previsualización y FIFO
4. `app/src/main/java/com/tuapp/inventario/manager/VencimientoManager.kt` - Mejoras sincronización

## 🚀 Comandos Git:

```bash
# 1. Verificar estado
git status

# 2. Agregar archivos modificados
git add version_info.json
git add app/build.gradle
git add app/src/main/java/com/tuapp/inventario/CargaActivity.kt
git add app/src/main/java/com/tuapp/inventario/manager/VencimientoManager.kt

# 3. Agregar APK (si está en el directorio)
git add app/build/outputs/apk/debug/inventario_app_v2.1.62.apk

# 4. Commit
git commit -m "v2.1.62: Correcciones críticas de previsualización y vencimientos

🔧 CORRECCIONES CRÍTICAS:
- Botones Guardar/Cancelar/Editar siempre visibles en previsualización
- ScrollView con altura dinámica (no oculta botones)
- Diálogo con altura máxima del 80% de pantalla
- Sistema FIFO mejorado: sincronización automática después del descuento
- Validación mejorada: no crea lotes automáticos (evita fechas incorrectas)
- Logs detallados para debugging de vencimientos
- Ajuste automático cuando hay más lotes que stock final
- Advertencias claras cuando faltan lotes por cargar manualmente

✅ MEJORAS DE PREVISUALIZACIÓN:
- Layout mejorado con weight para ScrollView
- Botones siempre visibles en la parte inferior
- Mejor experiencia de usuario

✅ MEJORAS DE VENCIMIENTOS:
- Sincronización automática de lotes con stock final
- Mejor manejo de diferencias entre lotes y stock
- Logs detallados para identificar problemas

Build: 88"

# 5. Push (si trabajas en una rama)
git push origin main

# O si trabajas en una rama específica:
# git push origin nombre-rama
```

## 📦 Ubicación del APK

El APK se encuentra en:
```
app/build/outputs/apk/debug/inventario_app_v2.1.62.apk
```

## 🚀 Subir APK con Git LFS

**IMPORTANTE:** El APK se sube a un repositorio separado usando Git LFS para evitar corrupción.

### Opción 1: Usar el script automatizado (Recomendado)

```powershell
# Ejecutar el script que sube el APK con Git LFS
.\subir_apk_main_lfs.ps1 -ApkPath "app\build\outputs\apk\debug\inventario_app_v2.1.62.apk" -VersionName "2.1.62"
```

El script:
- ✅ Verifica que Git LFS esté instalado
- ✅ Configura .gitattributes si es necesario
- ✅ Copia el APK al repositorio `inventario-app-updates`
- ✅ Agrega el APK con Git LFS
- ✅ Hace commit y push automáticamente

### Opción 2: Manual

```bash
# 1. Ir al repositorio de updates
cd ..\inventario-app-updates

# 2. Inicializar Git LFS (si no está inicializado)
git lfs install

# 3. Configurar .gitattributes (si no existe)
git lfs track "*.apk"
git add .gitattributes

# 4. Copiar el APK
copy ..\inventario_app\app\build\outputs\apk\debug\inventario_app_v2.1.62.apk .

# 5. Agregar con Git LFS
git add inventario_app_v2.1.62.apk

# 6. Verificar que está en LFS
git lfs ls-files

# 7. Commit y push
git commit -m "Agregar APK v2.1.62"
git push origin main
```

## ✅ Verificación Final

- [x] version_info.json actualizado
- [x] build.gradle actualizado
- [x] Código compilado sin errores
- [x] APK generado correctamente
- [x] Archivos modificados documentados
- [x] Script de subida actualizado a v2.1.62

