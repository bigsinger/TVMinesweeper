[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath,
    [switch]$ResetData
)
$ErrorActionPreference = 'Stop'
$script:Package = 'com.bigsinger.tvminesweeper'
$script:Component = "$script:Package/.ui.GameActivity"
$script:WorkRoot = 'E:\temp\TVMinesweeper'
$script:SnapshotNumber = 0
$script:Passed = 0
$script:Connected = $false
$script:Report = Join-Path $script:WorkRoot ('device-report-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.txt')
New-Item -ItemType Directory -Force $script:WorkRoot | Out-Null
$script:Utf8 = [Text.UTF8Encoding]::new($false)

function Write-Report([string]$Message) {
    $line = '[' + (Get-Date -Format 'HH:mm:ss') + '] ' + $Message
    Write-Host $line
    [IO.File]::AppendAllText($script:Report, $line + "`n", $script:Utf8)
}

function Invoke-Adb([string[]]$Arguments, [switch]$WithoutSerial) {
    $prefix = @()
    if (-not $WithoutSerial) { $prefix = @('-s', $script:DeviceSerial) }
    $savedPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $result = & $script:Adb @prefix @Arguments 2>&1
        $resultCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $savedPreference
    }
    $text = ($result | ForEach-Object { $_.ToString() }) -join "`n"
    if ($resultCode -ne 0) {
        throw "ADB 失败（$resultCode）：$($Arguments -join ' ')`n$text"
    }
    return $text
}

function Assert-That([bool]$Condition, [string]$Label, [string]$Details = '') {
    if (-not $Condition) { throw "断言失败：$Label。$Details" }
    $script:Passed++
    Write-Report "通过 $script:Passed：$Label $(if ($Details) { '(' + $Details + ')' })"
}

function Send-Keys([int[]]$Codes) {
    $arguments = @('shell', 'input', 'keyevent') + @($Codes | ForEach-Object { [string]$_ })
    Invoke-Adb -Arguments $arguments | Out-Null
}

function Read-Snapshot {
    $script:SnapshotNumber++
    $localFile = Join-Path $script:WorkRoot ('ui-' + $script:SnapshotNumber.ToString('000') + '.xml')
    $remoteFile = '/sdcard/tvminesweeper-uiautomator.xml'
    $dumpResult = Invoke-Adb -Arguments @('shell', 'uiautomator', 'dump', '--compressed', $remoteFile)
    if ($dumpResult -match 'ERROR|could not get idle state') {
        throw "无法读取无障碍界面：$dumpResult"
    }
    Invoke-Adb -Arguments @('pull', $remoteFile, $localFile) | Out-Null
    [xml]$document = [IO.File]::ReadAllText($localFile, [Text.Encoding]::UTF8)
    return [pscustomobject]@{ Document = $document; Path = $localFile }
}

function Read-Board($Snapshot = $null) {
    if ($null -eq $Snapshot) { $Snapshot = Read-Snapshot }
    $description = $null
    foreach ($node in $Snapshot.Document.SelectNodes('//node')) {
        $value = $node.GetAttribute('content-desc')
        if ($value -match '扫雷。状态：') { $description = $value; break }
    }
    $pattern = '状态：(准备|进行中|胜利|失败)。行：(\d+)。列：(\d+)。已开：(\d+)。旗帜：(\d+)。用时：(\d+)。暂停：(是|否)。当前格：(未翻开|已翻开|旗帜)'
    if (-not $description -or $description -notmatch $pattern) {
        throw "未发现约定的中文棋盘状态。请确认模拟器使用中文、已安装本项目新版本且当前没有遮挡弹窗。界面：$($Snapshot.Path)"
    }
    $states = @{ '准备' = 'READY'; '进行中' = 'PLAYING'; '胜利' = 'WON'; '失败' = 'LOST' }
    $cells = @{ '未翻开' = 'hidden'; '已翻开' = 'open'; '旗帜' = 'flag' }
    return [pscustomobject]@{
        State = $states[$Matches[1]]
        Row = [int]$Matches[2]
        Col = [int]$Matches[3]
        Opened = [int]$Matches[4]
        Flags = [int]$Matches[5]
        Elapsed = [long]$Matches[6]
        Paused = ($Matches[7] -eq '是')
        Cell = $cells[$Matches[8]]
        Description = $description
        Snapshot = $Snapshot.Path
    }
}

function Assert-Menu($Snapshot) {
    $found = $false
    foreach ($node in $Snapshot.Document.SelectNodes('//node')) {
        if ($node.GetAttribute('text') -eq '选择你的挑战') { $found = $true; break }
    }
    Assert-That $found 'MENU 打开难度菜单'
}

function Start-NewGame([int]$Difficulty) {
    Send-Keys @(82)
    Assert-Menu (Read-Snapshot)
    # Eight UP events place selection at the first row regardless of the previous option.
    Send-Keys @(19, 19, 19, 19, 19, 19, 19, 19)
    if ($Difficulty -gt 0) { Send-Keys @(1..$Difficulty | ForEach-Object { 20 }) }
    Send-Keys @(23)
    Start-Sleep -Milliseconds 400
    $board = Read-Board
    Assert-That ($board.State -eq 'READY' -and $board.Opened -eq 0 -and $board.Flags -eq 0 -and -not $board.Paused) '新局状态正确' $board.Description
    return $board
}

function Assert-Cursor($Board, [int]$Row, [int]$Col, [string]$Label) {
    Assert-That ($Board.Row -eq $Row -and $Board.Col -eq $Col) $Label "row=$($Board.Row), col=$($Board.Col)"
}

function Assert-PauseSavedTime($Before, $After, [double]$WallSeconds, [int]$HeldSeconds, [string]$Label) {
    # Dumps and ADB calls can be slow. Exclude only the guaranteed stationary pause interval.
    $allowed = [math]::Ceiling($WallSeconds - $HeldSeconds + 1)
    $delta = $After.Elapsed - $Before.Elapsed
    Assert-That ($delta -ge 0 -and $delta -le $allowed) $Label "elapsed delta=$delta, allowed=$allowed, paused interval=$HeldSeconds s"
}

$failed = $false
try {
    if ($AdbPath) {
        $script:Adb = $AdbPath
    } else {
        $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
        if ($adbCommand) { $script:Adb = $adbCommand.Source }
        elseif (Test-Path 'D:\Android\platform-tools\adb.exe') { $script:Adb = 'D:\Android\platform-tools\adb.exe' }
        else { throw '未找到 adb，请使用 -AdbPath 指定。' }
    }
    $devices = Invoke-Adb -Arguments @('devices') -WithoutSerial
    $available = @($devices -split "`r?`n" | Where-Object { $_ -match '^\S+\s+device$' } | ForEach-Object { ($_ -split '\s+')[0] })
    if ($Serial) {
        if ($available -notcontains $Serial) { throw "指定设备未连接或未授权：$Serial" }
        $script:DeviceSerial = $Serial
    } elseif ($available.Count -eq 1) {
        $script:DeviceSerial = $available[0]
    } else {
        throw '请连接一台已授权设备；如果存在多台设备，请使用 -Serial 指定。'
    }
    Write-Report "设备：$script:DeviceSerial；测试将重开对局，保留排行榜。报告：$script:Report"
    $installed = Invoke-Adb -Arguments @('shell', 'pm', 'path', $script:Package)
    if ($installed -notmatch '^package:') { throw '尚未安装 TVMinesweeper release APK。' }
    $script:Connected = $true
    if ($ResetData) {
        $clearResult = Invoke-Adb -Arguments @('shell', 'pm', 'clear', $script:Package)
        Assert-That ($clearResult -match 'Success') '按显式 -ResetData 参数清除测试应用数据'
    }
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $script:Component) | Out-Null
    Start-Sleep -Milliseconds 700
    $initial = Start-NewGame 0
    Assert-Cursor $initial 1 1 '初级新局光标位于左上角'
    Send-Keys @(19, 21, 19, 21)
    Assert-Cursor (Read-Board) 1 1 '上边界和左边界不越界'

    # One Android shell invocation emits both complete key events, avoiding host round-trip latency.
    Send-Keys @(23, 23)
    Start-Sleep -Milliseconds 400
    $double = Read-Board
    Assert-That ($double.Flags -eq 1 -and $double.Opened -eq 0 -and $double.Cell -eq 'flag') '双击 OK 只插旗，不翻开' $double.Description
    Send-Keys @(24)
    $unflagged = Read-Board
    Assert-That ($unflagged.Flags -eq 0 -and $unflagged.Opened -eq 0 -and $unflagged.Cell -eq 'hidden') '音量加取消旗帜'
    Send-Keys @(25)
    $flagged = Read-Board
    Assert-That ($flagged.Flags -eq 1 -and $flagged.Opened -eq 0) '音量减插旗'
    Invoke-Adb -Arguments @('shell', 'input', 'keyevent', '--longpress', '24') | Out-Null
    $longPress = Read-Board
    Assert-That ($longPress.Flags -eq 0 -and $longPress.Opened -eq 0) '长按音量键只切换一次旗帜'
    Send-Keys @(23)
    Start-Sleep -Milliseconds 450
    $opened = Read-Board
    Assert-That ($opened.Opened -gt 0 -and $opened.State -eq 'PLAYING' -and $opened.Cell -eq 'open') '单击 OK 安全翻开并开始计时' $opened.Description

    Send-Keys @(22, 20)
    $pauseWatch = [Diagnostics.Stopwatch]::StartNew()
    $beforeMenu = Read-Board
    Send-Keys @(82)
    Assert-Menu (Read-Snapshot)
    Send-Keys @(20)
    $menuHold = 6
    Start-Sleep -Seconds $menuHold
    Send-Keys @(4)
    $afterMenu = Read-Board
    $pauseWatch.Stop()
    Assert-Cursor $afterMenu $beforeMenu.Row $beforeMenu.Col '菜单选择后返回保留棋盘光标'
    Assert-That ($afterMenu.Opened -eq $beforeMenu.Opened -and $afterMenu.Flags -eq $beforeMenu.Flags -and -not $afterMenu.Paused) '取消菜单保留对局并恢复计时'
    Assert-PauseSavedTime $beforeMenu $afterMenu $pauseWatch.Elapsed.TotalSeconds $menuHold '菜单停留期间暂停计时'
    Send-Keys @(82)
    Assert-Menu (Read-Snapshot)
    Send-Keys @(82)
    Assert-Cursor (Read-Board) $beforeMenu.Row $beforeMenu.Col '再次 MENU 关闭菜单并保留光标'

    $null = Start-NewGame 1
    Send-Keys @((1..35 | ForEach-Object { 22 }) + (1..20 | ForEach-Object { 20 }))
    Assert-Cursor (Read-Board) 16 16 '中级为 16×16，右下边界不越界'
    $null = Start-NewGame 2
    Send-Keys @((1..35 | ForEach-Object { 22 }) + (1..20 | ForEach-Object { 20 }))
    Assert-Cursor (Read-Board) 16 30 '高级为 30×16，右下边界不越界'

    # A medium board makes a first-click instant win extremely unlikely during lifecycle checks.
    $null = Start-NewGame 1
    Send-Keys @((1..20 | ForEach-Object { 22 }) + (1..20 | ForEach-Object { 20 }) + @(24))
    Send-Keys @((1..20 | ForEach-Object { 21 }) + (1..20 | ForEach-Object { 19 }) + @(22, 20, 23))
    Start-Sleep -Milliseconds 450
    $lifecycle = Read-Board
    Assert-That ($lifecycle.State -eq 'PLAYING' -and $lifecycle.Opened -gt 0 -and $lifecycle.Flags -eq 1) '生命周期测试对局已开始并保留一面旗帜'
    $backgroundWatch = [Diagnostics.Stopwatch]::StartNew()
    $beforeBackground = Read-Board
    Send-Keys @(3)
    $backgroundHold = 6
    Start-Sleep -Seconds $backgroundHold
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $script:Component) | Out-Null
    $afterBackground = Read-Board
    $backgroundWatch.Stop()
    Assert-That ($afterBackground.Paused -and $afterBackground.Opened -eq $beforeBackground.Opened -and $afterBackground.Flags -eq $beforeBackground.Flags) '从后台返回保留对局并显示暂停'
    Assert-Cursor $afterBackground $beforeBackground.Row $beforeBackground.Col '从后台返回保留光标'
    Assert-PauseSavedTime $beforeBackground $afterBackground $backgroundWatch.Elapsed.TotalSeconds $backgroundHold '后台期间暂停计时'
    Send-Keys @(23)
    Start-Sleep -Milliseconds 400
    $resumed = Read-Board
    Assert-That (-not $resumed.Paused -and $resumed.Opened -eq $afterBackground.Opened -and $resumed.Flags -eq $afterBackground.Flags) 'OK 恢复游戏，不额外翻开格子'

    $beforeRestart = Read-Board
    Send-Keys @(3)
    Start-Sleep -Milliseconds 1000
    Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $script:Package) | Out-Null
    Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $script:Component) | Out-Null
    $restored = Read-Board
    Assert-That ($restored.State -eq 'PLAYING' -and $restored.Paused -and $restored.Opened -eq $beforeRestart.Opened -and $restored.Flags -eq $beforeRestart.Flags) '重新启动恢复已保存对局并暂停'
    Assert-Cursor $restored $beforeRestart.Row $beforeRestart.Col '重新启动恢复光标'
    Assert-That ($restored.Elapsed -ge $beforeRestart.Elapsed) '重新启动恢复计时'
    Write-Report '功能断言完成，准备恢复初级空白新局。'
} catch {
    $failed = $true
    Write-Report ('失败：' + $_.Exception.Message)
} finally {
    if ($script:Connected) {
        try {
            Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', $script:Component) | Out-Null
            # Close an existing dialog only when the board is absent from the active accessibility window.
            $cleanupSnapshot = Read-Snapshot
            $hasBoard = $false
            foreach ($node in $cleanupSnapshot.Document.SelectNodes('//node')) {
                if ($node.GetAttribute('content-desc') -match '扫雷。状态：') { $hasBoard = $true; break }
            }
            if (-not $hasBoard) { Send-Keys @(4) }
            $finalBoard = Start-NewGame 0
            Assert-Cursor $finalBoard 1 1 '结束时恢复初级新局并退出所有弹窗'
            Invoke-Adb -Arguments @('shell', 'rm', '-f', '/sdcard/tvminesweeper-uiautomator.xml') | Out-Null
        } catch {
            $failed = $true
            Write-Report ('收尾失败：' + $_.Exception.Message)
        }
    }
}
Write-Report "结果：$(if ($failed) { '失败' } else { '全部通过' })；通过断言数：$script:Passed。"
Write-Report "文本报告：$script:Report；无障碍 XML：$script:WorkRoot\ui-*.xml。全程未截图、未修改设备分辨率。"
if ($failed) { exit 1 }
exit 0
