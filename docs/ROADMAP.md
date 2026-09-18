# Roadmap

GithubBckp is intentionally feature-frozen around a small personal-use scope.

## Current scope

- read-only GitHub personal access token;
- repository discovery and selection;
- one local `.tar.gz` Git mirror per selected repository;
- incremental Git fetch/update before recompression;
- daily or weekly automatic updates;
- foreground notification for every repository backup job;
- mirror health dashboard.

## Non-goals

The following are intentionally outside the product:

- Google Drive or other app-managed cloud providers;
- multiple retained backup generations;
- source snapshot mode;
- restore/publish workflows;
- destructive GitHub operations;
- GitHub issues, pull requests, release assets, or wiki archival;
- dedicated Git LFS transfer logic;
- audit/report export;
- recovery drills;
- public Play Store distribution.

Future work should be limited to correctness, reliability, Android compatibility, Git compatibility, storage safety, and usability of this narrow workflow.
