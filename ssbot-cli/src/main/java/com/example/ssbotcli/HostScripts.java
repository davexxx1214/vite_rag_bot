package com.example.ssbotcli;

final class HostScripts {
  static final String DISCOVER = """
      $all=@(Get-CimInstance Win32_Service | Sort-Object Name)
      @{services=@($all | Select-Object -First 200 Name,DisplayName,State,StartMode,PathName,ProcessId);
        total=$all.Count; truncated=($all.Count -gt 200)}
      """;
  static final String SERVICE = """
      $s=Get-CimInstance Win32_Service -Filter ("Name='"+$a.name+"'")
      if (!$s) { @{error='Service not found'}; return }
      $process=$null
      if ($s.ProcessId -gt 0) {
        $process=Get-CimInstance Win32_Process -Filter ('ProcessId='+$s.ProcessId) |
          Select-Object ProcessId,ParentProcessId,ExecutablePath,WorkingSetSize,CreationDate
      }
      $deps=@((Get-Service -Name $a.name).ServicesDependedOn | Select-Object Name,@{n='Status';e={$_.Status.ToString()}})
      @{service=($s | Select-Object Name,DisplayName,State,StartMode,PathName,ProcessId,ExitCode);
        process=$process; dependencies=$deps}
      """;
  static final String PORTS = """
      $connections=@(Get-NetTCPConnection -State Listen | Where-Object { $a.ports -contains [int]$_.LocalPort })
      @{listeners=@($connections | Select-Object -First 100 LocalAddress,LocalPort,OwningProcess);
        truncated=($connections.Count -gt 100)}
      """;
  static final String IIS = """
      if (!(Get-Module -ListAvailable WebAdministration)) { @{error='IIS WebAdministration module unavailable'}; return }
      Import-Module WebAdministration
      $site=Get-Website | Where-Object { $_.Name -eq $a.site }
      if (!$site) { @{error='IIS site not found'}; return }
      $pool=$site.applicationPool
      if ($a.pool) { $pool=$a.pool }
      $state=(Get-WebAppPoolState -Name $pool).Value
      @{site=($site | Select-Object Name,ID,State,PhysicalPath,ApplicationPool);
        bindings=@($site.Bindings.Collection | Select-Object protocol,bindingInformation);
        app_pool=@{name=$pool; state=$state}}
      """;
  static final String EVENTS = """
      $results=@(); $issues=@()
      foreach ($log in @('System','Application')) {
        $filter=@{LogName=$log; StartTime=(Get-Date).AddMinutes(-[int]$a.minutes); Level=@(1,2,3)}
        if ($a.providers.Count -gt 0) { $filter.ProviderName=@($a.providers) }
        try {
          $results+=@(Get-WinEvent -FilterHashtable $filter -MaxEvents ([int]$a.limit) -ErrorAction Stop |
            Select-Object @{n='time';e={$_.TimeCreated.ToUniversalTime().ToString('o')}},Id,RecordId,ProviderName,LogName,
              @{n='message';e={ $m=$_.Message; if ($m.Length -gt 2000) {$m.Substring(0,2000)} else {$m} }})
        } catch {
          if ($_.FullyQualifiedErrorId -notlike 'NoMatchingEventsFound*') {
            $issues+=@{log=$log; error='Event query failed'; code=$_.CategoryInfo.Category.ToString()}
          }
        }
      }
      @{events=@($results | Sort-Object time -Descending | Select-Object -First ([int]$a.limit));
        issues=$issues; scope='Provider/time candidates; not proof of service causality'; limit=[int]$a.limit}
      """;
  static final String HOST = """
      @{host_name=$env:COMPUTERNAME;
        os=(Get-CimInstance Win32_OperatingSystem | Select-Object Caption,Version,LastBootUpTime,FreePhysicalMemory,TotalVisibleMemorySize);
        disks=@(Get-CimInstance Win32_LogicalDisk -Filter 'DriveType=3' | Select-Object DeviceID,Size,FreeSpace);
        runtimes=@(Get-Command java,dotnet -ErrorAction SilentlyContinue | Select-Object Name,Source);
        pending_reboot=((Test-Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Component Based Servicing\\RebootPending') -or
          (Test-Path 'HKLM:\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\WindowsUpdate\\Auto Update\\RebootRequired'))}
      """;
}
