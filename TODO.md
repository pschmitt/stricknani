# TODO

Prioritized refactor/tech-debt tasks for Stricknani.

## Status Legend

- `todo`: not started
- `wip`: in progress
- `blocked`: waiting on a dependency/decision
- `done`: completed and merged

## Priority Rubric

- `P0`: high impact, low to medium complexity
- `P1`: high impact, higher complexity or some risk
- `P2`: medium impact or mostly cleanup
- `P3`: nice-to-have

## Task List

| Priority | Status | Task                                                                                                 | Impact | Complexity | Primary Files                                                                                                                                |
| -------- | ------ | ---------------------------------------------------------------------------------------------------- | ------ | ---------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| P1       | done   | AI-powered project import from arbitrary files (images, PDFs, etc.)                                  | High   | Medium     | New import service, AI integration, file processing                                                                                          |
| P0       | done   | Refactor entire import mechanism for better extensibility and maintainability                        | High   | Medium     | Import-related modules, service layer refactoring                                                                                            |
| P3       | todo   | Add `other_materials` text field to projects for extras like buttons/zippers                         | Medium | Low        | `stricknani/templates/projects/form.html`, `stricknani/models/project.py`, `stricknani/routes/projects.py`, `stricknani/services/projects/*` |
| P3       | todo   | Replace runtime Tailwind-in-browser with a prebuilt static CSS bundle for performance and easier CSP | High   | High       | `stricknani/templates/base.html`, build tooling (`justfile`, `flake.nix`)                                                                    |

## Notes

- Keep the smallest possible theme-init snippet inline to avoid FOUC; everything else should prefer static JS/CSS.
- When refactoring JS into static files, avoid inline translation strings in templates; pass a JSON config object instead.
- Maintain UI consistency between Projects and Yarns when extracting shared components.

## Import Refactoring Progress

### Phase 1 (Completed): Image Handling Consolidation

Created unified image importing infrastructure at `stricknani/importing/images/`:

- **`constants.py`**: Centralized all image import constants (size limits, timeouts, allowed types)
- **`validator.py`**: URL and content-type validation functions
- **`deduplicator.py`**: Checksum and similarity-based duplicate detection
- **`downloader.py`**: Unified `ImageDownloader` class for batch image downloads with built-in validation, deduplication, and thumbnail detection
- **`__init__.py`**: Clean public API exports

**Key improvements:**
- Single source of truth for constants (eliminated duplication across 4+ files)
- `ImageDownloader` class provides reusable interface for all image import operations
- Backward compatibility maintained via re-exports in `stricknani.importing` and `stricknani.utils.importer`
- All 26 import-related tests pass
- Ruff linting and MyPy strict type checking pass

**Architecture:**
```
stricknani/importing/images/
├── constants.py      # Configuration
├── validator.py      # URL/content validation
├── deduplicator.py   # Checksum + SSIM duplicate detection
├── downloader.py     # Unified ImageDownloader class
└── __init__.py       # Public API
```

### Phase 2 (Completed): Import Pipeline Architecture

Created Source → Extractor → Target abstraction for flexible import workflows:

**Core Components:**
- **`models.py`**: Data models (`RawContent`, `ExtractedData`, `ExtractedStep`, `ExtractedYarn`, `ImportResult`)
- **`pipeline.py`**: `ImportPipeline` orchestrator that coordinates the full flow
- **`sources/`**: Input sources
  - `URLSource` - Fetches HTML/text from HTTP URLs
- **`extractors/`**: Content extractors
  - `HTMLExtractor` - Wraps existing PatternImporter for HTML content
  - `FallbackExtractor` - Minimal extraction as last resort
- **`targets/`**: Output targets (base classes for Project, Yarn, Step)

**Key Improvements:**
- Clean separation of concerns between fetching, parsing, and persistence
- Extensible design: new sources/extractors/targets can be added independently
- Backward compatibility: all existing code continues to work
- All 17 import tests pass
- Full Ruff + MyPy strict compliance

**Example Usage:**
```python
from stricknani.importing import ImportPipeline, URLSource, HTMLExtractor
from stricknani.importing.targets.projects import ProjectTarget

pipeline = ImportPipeline(db, owner_id=user.id)
result = await pipeline.run(
    source=URLSource("https://example.com/pattern"),
    extractors=[HTMLExtractor()],
    target=ProjectTarget(db, user.id),
)
```

**Architecture:**
```
stricknani/importing/
├── __init__.py          # Public API exports
├── models.py            # Data models
├── pipeline.py          # ImportPipeline orchestrator
├── sources/
│   ├── __init__.py      # ImportSource base class
│   └── url.py           # URLSource implementation
├── extractors/
│   ├── __init__.py      # ContentExtractor base class
│   └── html.py          # HTMLExtractor implementation
├── targets/
│   └── __init__.py      # ImportTarget base class
└── images/              # Phase 1 image handling
    └── ...
```

### Phase 3 (Completed): AI-Powered File Import

Created AI-powered import infrastructure for arbitrary files (images, PDFs, text):

**New Components:**
- **`sources/file.py`**: `FileSource` and `MultiFileSource` for uploaded files
  - Auto-detects content type from file extension
  - Supports images, PDFs, HTML, text, markdown
  - `MultiFileSource` aggregates multiple files (e.g., multiple photos)
- **`extractors/ai.py`**: `AIExtractor` using OpenAI GPT-4 Vision
  - Analyzes images to extract pattern data (name, yarn, needles, gauge, etc.)
  - Processes text/HTML content with AI enhancement
  - Gracefully handles missing OpenAI package or API key
- **`extractors/pdf.py`**: `PDFExtractor` for PDF parsing
  - Supports PyMuPDF (fitz) and pypdf backends
  - Extracts text from all pages
  - Can extract embedded images for AI analysis

**Example Usage:**
```python
from stricknani.importing import ImportPipeline, FileSource, AIExtractor

# Import from a single image
source = FileSource("/path/to/photo.jpg")
pipeline = ImportPipeline(db, owner_id=user.id)
result = await pipeline.run(
    source=source,
    extractors=[AIExtractor(), FallbackExtractor()],
    target=ProjectTarget(db, user.id),
)

# Import multiple photos
multi_source = MultiFileSource(["photo1.jpg", "photo2.jpg", "photo3.jpg"])
result = await pipeline.run(
    source=multi_source,
    extractors=[AIExtractor()],
    target=ProjectTarget(db, user.id),
)
```

**Architecture:**
```
stricknani/importing/
├── sources/
│   ├── file.py          # FileSource, MultiFileSource
│   └── url.py           # URLSource
├── extractors/
│   ├── ai.py            # AIExtractor (GPT-4 Vision)
│   ├── html.py          # HTMLExtractor
│   ├── pdf.py           # PDFExtractor
│   └── fallback.py      # FallbackExtractor
└── ...
```

### Summary: Complete Import Refactoring

All three phases of the import refactoring are now complete:

1. **Phase 1**: Unified image handling with `ImageDownloader` and deduplication
2. **Phase 2**: Pipeline architecture with Source → Extractor → Target flow
3. **Phase 3**: AI-powered file import for images, PDFs, and arbitrary files

**Key Benefits:**
- Extensible: Easy to add new sources (Dropbox, Google Drive, etc.)
- Composable: Chain extractors for progressive enhancement
- Testable: Each component can be unit tested independently
- Backward compatible: All existing code continues to work
- Type safe: Full MyPy strict compliance
- Ready for AI: Structured for easy AI integration

**26 tests pass** - No regressions introduced.
