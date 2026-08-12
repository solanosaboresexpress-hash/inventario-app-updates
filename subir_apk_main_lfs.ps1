# Script para subir APK a la rama main usando Git LFS
# Esto evita que el APK se corrompa

param(
    [string]$ApkPath = "app\build\outputs\apk\debug\inventario_app_v2.1.104.apk",
    [string]$RepoPath = "",
    [string]$VersionName = "2.1.104"
)

Write-Host "Subiendo APK a rama main con Git LFS..." -ForegroundColor Cyan

# Verificar que el APK existe
if (-not (Test-Path $ApkPath)) {
    Write-Host "Error: El archivo APK no existe: $ApkPath" -ForegroundColor Red
    exit 1
}

# Verificar tamaño del APK
$apkSize = (Get-Item $ApkPath).Length
$apkSizeMB = [math]::Round($apkSize / 1MB, 2)
Write-Host "Tamano del APK: $apkSizeMB MB ($apkSize bytes)" -ForegroundColor Green

if ($apkSize -lt 1000000) {
    Write-Host "Error: El APK es muy pequeno. Esta corrupto." -ForegroundColor Red
    exit 1
}

# Si no se especifica RepoPath, buscar el repositorio de updates
if (-not $RepoPath) {
    # Intentar encontrar el repositorio de updates
    $possiblePaths = @(
        "..\inventario-app-updates",
        "..\..\inventario-app-updates",
        "C:\Users\Lucas\AndroidStudioProjects\inventario-app-updates"
    )
    
    foreach ($path in $possiblePaths) {
        if (Test-Path $path -PathType Container) {
            if (Test-Path (Join-Path $path ".git")) {
                $RepoPath = $path
                Write-Host "Repositorio encontrado: $RepoPath" -ForegroundColor Green
                break
            }
        }
    }
    
    if (-not $RepoPath) {
        Write-Host "No se encontro el repositorio de updates." -ForegroundColor Yellow
        Write-Host "Ingresa la ruta al repositorio inventario-app-updates:" -ForegroundColor Yellow
        $RepoPath = Read-Host
    }
}

if (-not (Test-Path $RepoPath)) {
    Write-Host "Error: El directorio no existe: $RepoPath" -ForegroundColor Red
    exit 1
}

if (-not (Test-Path (Join-Path $RepoPath ".git"))) {
    Write-Host "Error: No es un repositorio Git: $RepoPath" -ForegroundColor Red
    exit 1
}

# Verificar que Git LFS esta instalado
try {
    $lfsVersion = git lfs version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "Git LFS no instalado"
    }
    Write-Host "Git LFS instalado: $lfsVersion" -ForegroundColor Green
} catch {
    Write-Host "Error: Git LFS no esta instalado." -ForegroundColor Red
    Write-Host "Instalalo desde: https://git-lfs.github.com/" -ForegroundColor Yellow
    Write-Host "O con: winget install --id GitHub.cli" -ForegroundColor Yellow
    exit 1
}

# Cambiar al directorio del repositorio
Push-Location $RepoPath

try {
    # Inicializar Git LFS si no esta inicializado
    Write-Host "`nInicializando Git LFS..." -ForegroundColor Cyan
    git lfs install 2>&1 | Out-Null
    
    # Verificar/crear .gitattributes
    $gitattributesPath = Join-Path $RepoPath ".gitattributes"
    $needsLfsTrack = $true
    
    if (Test-Path $gitattributesPath) {
        $content = Get-Content $gitattributesPath -Raw
        if ($content -match "\.apk.*filter=lfs") {
            Write-Host ".gitattributes ya configurado para APK" -ForegroundColor Green
            $needsLfsTrack = $false
        }
    }
    
    if ($needsLfsTrack) {
        Write-Host "Configurando Git LFS para archivos APK..." -ForegroundColor Cyan
        git lfs track "*.apk" 2>&1 | Out-Null
        
        # Asegurar que .gitattributes existe
        if (-not (Test-Path $gitattributesPath)) {
            "*.apk filter=lfs diff=lfs merge=lfs -text" | Out-File -FilePath $gitattributesPath -Encoding UTF8 -NoNewline
        }
        
        git add .gitattributes 2>&1 | Out-Null
        Write-Host ".gitattributes configurado" -ForegroundColor Green
    }
    
    # Copiar el APK al repositorio
    $apkName = Split-Path $ApkPath -Leaf
    $destPath = Join-Path $RepoPath $apkName
    
    Write-Host "`nCopiando APK al repositorio..." -ForegroundColor Cyan
    Copy-Item $ApkPath $destPath -Force
    
    # Verificar que se copio correctamente
    $copiedSize = (Get-Item $destPath).Length
    if ($copiedSize -ne $apkSize) {
        Write-Host "Error: El archivo no se copio correctamente" -ForegroundColor Red
        Write-Host "Original: $apkSize bytes, Copiado: $copiedSize bytes" -ForegroundColor Red
        exit 1
    }
    Write-Host "APK copiado correctamente: $copiedSize bytes" -ForegroundColor Green
    
    # Agregar con Git LFS el APK y la metadata
    Write-Host "`nAgregando APK y version_info.json con Git..." -ForegroundColor Cyan
    git add $apkName 2>&1 | Out-Null
    if (Test-Path (Join-Path $RepoPath "version_info.json")) {
        git add version_info.json 2>&1 | Out-Null
    }
    
    # Verificar que se agrego con LFS
    $lfsFiles = git lfs ls-files 2>&1
    if ($lfsFiles -match $apkName) {
        Write-Host "APK agregado correctamente con Git LFS" -ForegroundColor Green
    } else {
        Write-Host "ADVERTENCIA: El APK puede no estar siendo manejado por Git LFS" -ForegroundColor Yellow
        Write-Host "Verificando estado..." -ForegroundColor Yellow
        git status --short | Select-String $apkName
    }
    
    # Mostrar estado
    Write-Host "`nEstado del repositorio:" -ForegroundColor Cyan
    git status --short
    
    # Preguntar si hacer commit y push
    Write-Host "`n¿Deseas hacer commit y push ahora? (s/n):" -ForegroundColor Yellow
    $confirm = Read-Host
    
    if ($confirm -eq "s") {
        Write-Host "`nHaciendo commit..." -ForegroundColor Cyan
        git commit -m "Agregar APK v$VersionName" 2>&1
        
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Commit exitoso" -ForegroundColor Green
            
            Write-Host "`nHaciendo push..." -ForegroundColor Cyan
            git push origin main 2>&1
            
            if ($LASTEXITCODE -eq 0) {
                Write-Host "Push exitoso" -ForegroundColor Green
                Write-Host "`nAPK subido correctamente a la rama main" -ForegroundColor Green
                Write-Host "URL: https://github.com/solanosaboresexpress-hash/inventario-app-updates/raw/main/$apkName" -ForegroundColor Cyan
            } else {
                Write-Host "Error en push. Verifica tus credenciales." -ForegroundColor Red
            }
        } else {
            Write-Host "Error en commit." -ForegroundColor Red
        }
    } else {
        Write-Host "`nAPK preparado para commit. Ejecuta manualmente:" -ForegroundColor Yellow
        Write-Host "  git commit -m 'Agregar APK v$VersionName'" -ForegroundColor White
        Write-Host "  git push origin main" -ForegroundColor White
    }
    
} finally {
    Pop-Location
}

Write-Host "`nProceso completado." -ForegroundColor Green

