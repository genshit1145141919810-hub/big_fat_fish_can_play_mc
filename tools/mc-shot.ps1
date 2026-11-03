# Focus the Minecraft window, optionally send keystrokes, then capture it to a PNG.
# Used by the agent for real in-game smoke tests. ASCII only.
param(
    [string]$Keys = '',
    [string]$Shot = '',
    [int]$WaitMs = 800
)

Add-Type -AssemblyName System.Windows.Forms, System.Drawing

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win32 {
    [DllImport("user32.dll")] public static extern IntPtr FindWindow(string cls, string title);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

$hwnd = [Win32]::FindWindow($null, 'Minecraft 1.21.11')
if ($hwnd -eq [IntPtr]::Zero) {
    $procs = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' }
    if ($procs) { $hwnd = $procs[0].MainWindowHandle }
}
if ($hwnd -eq [IntPtr]::Zero) { Write-Host 'window not found'; exit 1 }

[void][Win32]::ShowWindow($hwnd, 9)
[void][Win32]::SetForegroundWindow($hwnd)
Start-Sleep -Milliseconds 400

if ($Keys) {
    [System.Windows.Forms.SendKeys]::SendWait($Keys)
    Start-Sleep -Milliseconds $WaitMs
}

if ($Shot) {
    $rect = New-Object Win32+RECT
    [void][Win32]::GetWindowRect($hwnd, [ref]$rect)
    $w = $rect.Right - $rect.Left
    $h = $rect.Bottom - $rect.Top
    if ($w -le 0 -or $h -le 0) { Write-Host 'bad window rect'; exit 1 }
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bmp.Size)
    $bmp.Save($Shot, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Host "shot: $Shot ($w x $h)"
}
