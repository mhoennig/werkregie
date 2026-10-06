# System-Architektur

Wie die Werk-Bausteine zusammenarbeiten: von der Planung über Code, Build und Review bis zur Auswertung.
Werkregie ist noch nicht gebaut; heute übernimmt der Mensch ihre Rolle, daher sind ihre Kanten gestrichelt.

```mermaid
%%{init: {"theme": "base", "themeVariables": {"background": "#ffffff", "mainBkg": "#ffffff", "primaryColor": "#ffffff", "primaryBorderColor": "#bdbdbd", "primaryTextColor": "#2b2b2b", "clusterBkg": "#f7f7f7", "clusterBorder": "#d0d0d0", "lineColor": "#8a8a8a", "edgeLabelBackground": "#ffffff", "fontFamily": "IBM Plex Sans, sans-serif"}}}%%
flowchart LR
  classDef mensch fill:#f4e3c1,stroke:#8a6d1e,color:#2b2b2b
  classDef agent fill:#dfeee0,stroke:#2e7d32,color:#2b2b2b
  classDef planung fill:#d6e4f0,stroke:#1f5f8b,color:#2b2b2b
  classDef regie fill:#fde9d9,stroke:#b5541c,stroke-dasharray:5 3,color:#2b2b2b
  classDef werkator fill:#e4eef3,stroke:#0e6b7d,color:#2b2b2b
  classDef review fill:#e8ddf1,stroke:#6a3d9a,color:#2b2b2b
  classDef gitea fill:#fbfbfb,stroke:#b8b2a6,color:#2b2b2b
  classDef extern fill:#ffffff,stroke:#9e9e9e,stroke-dasharray:2 2,color:#2b2b2b
  classDef werkzeug fill:#fff8d6,stroke:#9a7b00,color:#2b2b2b

  M(["Mensch<br/>Entwickler und Freigeber"]):::mensch

  subgraph WS["Arbeitsplatz"]
    CC["Claude Code<br/>Coding-Agent"]:::agent
    WBF[".werkbaum-Dateien<br/>im Repository"]:::planung
  end

  subgraph PLAN["Planung"]
    WB["Werkbaum<br/>Planungswerkzeug:<br/>Notation und Live-Editor<br/>werkbaum.javagil.de"]:::planung
  end

  subgraph STEUER["Steuerung – geplant"]
    WR["Werkregie<br/>Dashboard und Controller:<br/>startet Agenten, liest Status,<br/>bündelt Kosten und Freigaben"]:::regie
  end

  subgraph FORGE["Gitea – git.javagil.de, dev.hostsharing.net"]
    REPO[("Repositories<br/>Branches, Pull-Requests")]:::gitea
    PRS["PR-Status, Reviews,<br/>Branch-Schutz:<br/>Prüfungen grün + 1 Approval"]:::gitea
  end
  GH["GitHub<br/>öffentlicher Spiegel"]:::extern

  subgraph HOST["Hostsharing Managed Webspace"]
    subgraph WKT["Werkator – CI/CD"]
      WK["Werkator-Dienst<br/>Polling, Build-Definitionen,<br/>Web-Oberfläche"]:::werkator
      WD["Werkdock<br/>Sandbox über bwrap, ohne root;<br/>Docker-Engine-API für Testcontainers"]:::werkator
      BUILD["Builds je Branch<br/>Gradle, Tests, Werkcrappit"]:::werkator
      AIR["werkrevisor-CLI<br/>als Build ai-review<br/>im werkrevisor-Rootfs"]:::review
    end
    subgraph WRS["werkrevisor-Dienst"]
      STATS["Statistik-Dienst<br/>Spring Boot, SQLite<br/>werkrevisor.javagil.de"]:::review
      DASH["Dashboard<br/>Web Component<br/>werkrevisor-dashboard"]:::review
    end
  end

  subgraph EXT["Externe Dienste"]
    OR["OpenRouter<br/>LLM, z. B. Claude Sonnet"]:::extern
    JEV["TypeSafe Jev<br/>Klassifikator und Finding-Richter"]:::extern
  end

  CRAP["Werkcrappit<br/>Mutation-CRAP-Metrik,<br/>Gradle- und PIT-Plugin"]:::werkzeug
  PROBE["Werkprobe<br/>Testcontainers-Arbeitsprobe<br/>für Werkdock"]:::werkzeug

  %% Planung und Code
  M -- "plant im Dialog" --> WB
  WB -- "Notation" --- WBF
  M -- "beauftragt, prüft" --> CC
  CC -- "liest Plan, setzt Status" --> WBF
  CC -- "push, WIP-PR,<br/>WIP entfernen wenn fertig" --> REPO
  REPO -. "Spiegel" .-> GH

  %% Build
  WK -- "pollt Branches und PRs" --> REPO
  WK -- "startet" --> BUILD
  WK -- "startet" --> AIR
  BUILD -- "läuft in" --> WD
  AIR -- "läuft in" --> WD
  CRAP -. "Plugin im Build" .-> BUILD
  PROBE -. "prüft" .-> WD
  WK -- "Commit-Status werkator" --> PRS

  %% Review
  AIR -- "Diff, PR-Doc, Kontext" --> OR
  AIR -- "Fragen an Szenarien,<br/>Findings, Changesets" --> JEV
  AIR -- "Ergebnis-Kommentar,<br/>Zeilenkommentare,<br/>Approval, Merge planen" --> PRS
  PRS -- "Merge, wenn grün<br/>und freigegeben" --> REPO
  M -- "Approval bei<br/>YELLOW oder ORANGE" --> PRS

  %% Auswertung
  AIR -- "Usage-Record<br/>Kosten, Tokens, Verdikt" --> STATS
  STATS --> DASH
  DASH -. "eingebettet, später" .-> WK
  DASH -. "eingebettet" .-> WR

  %% Steuerung
  WR -. "liest und schreibt Plan" .-> WBF
  WR -. "startet und überwacht" .-> CC
  WR -. "liest Build-Status" .-> WK
  WR -. "liest PRs, Freigaben" .-> PRS
  M -. "steuert über" .-> WR
```

## Bausteine

| Baustein | Rolle |
|---|---|
| Werkbaum | Planung als Textnotation mit Und/Oder-Zerlegung; die `.werkbaum`-Dateien liegen im jeweiligen Repository. |
| Claude Code | Coding-Agent am Arbeitsplatz; legt Pull-Requests als WIP an und entfernt WIP, wenn implementiert, getestet und dokumentiert. |
| Gitea | Quelle der Wahrheit für Code, Pull-Requests, Status-Prüfungen und Branch-Schutz; GitHub ist nur Spiegel. |
| Werkator | Leichtgewichtiges CI/CD auf dem Managed Webspace; baut jeden Branch und Pull-Request und meldet den Status an Gitea. |
| Werkdock | Sandbox über `bwrap` ohne root, kompatibel zur Docker-Engine-API, soweit Testcontainers sie braucht. |
| werkrevisor | KI-Review als Werkator-Build: Regeln, LLM über OpenRouter und Jev über TypeSafe; gibt frei und plant den Merge. |
| werkrevisor-Dienst | Sammelt Kosten und Tokens aller Reviews und zeigt sie als einbettbares Dashboard. |
| Werkcrappit | Mutation-CRAP-Metrik als Gradle- und PIT-Plugin, im Build eines Projekts. |
| Werkprobe | Arbeitsprobe, die belegt, dass Testcontainers gegen Werkdock läuft. |
| Werkregie | Geplant: Dashboard und Controller, der Agenten startet, Status aus Werkator und Gitea liest und das werkrevisor-Dashboard einbettet. |
