# Scheduled backups

Automatic mirror updates have two cadence choices:

- daily: 24-hour periodic interval;
- weekly: 7-day periodic interval.

Android WorkManager is opportunistic, so these are cadence targets rather than exact wall-clock alarms.

The periodic controller selects repositories currently marked for backup and enqueues the same repository worker used by manual backups.

Each actual repository backup:

- requires network connectivity;
- runs as foreground data-sync work;
- shows an ongoing device notification;
- updates the existing local mirror when one exists;
- creates the first mirror when one does not exist.

Scheduled work also asks Android for battery-not-low and storage-not-low conditions before the periodic controller runs.

The health dashboard treats a scheduled mirror as stale after two missed cadence windows.
