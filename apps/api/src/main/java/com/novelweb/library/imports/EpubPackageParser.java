package com.novelweb.library.imports;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads a bounded EPUB 3 package without extracting archive entries or resolving external
 * resources. Returned text is a short plain-text preview; source markup and assets never leave
 * private staging.
 */
@Component
@Profile("postgres")
public class EpubPackageParser {

  private static final String CONTAINER_PATH = "META-INF/container.xml";
  private static final String PACKAGE_MIME = "application/oebps-package+xml";
  private static final String XHTML_MIME = "application/xhtml+xml";
  private static final String EPUB_NAMESPACE = "http://www.idpf.org/2007/ops";
  private static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";
  private static final String EPUB_MIMETYPE = "application/epub+zip";
  private static final int METADATA_TEXT_LIMIT = 4096;
  private static final int NAV_LABEL_LIMIT = 512;
  private static final int PREVIEW_TEXT_LIMIT = 280;
  private static final int MAX_PREVIEW_CHAPTERS = 2000;
  private static final int MAX_PREVIEW_TOC_ENTRIES = 10000;
  private static final int MAX_SOURCE_SEGMENTS = 5000;
  private static final int MAX_SEGMENT_CODE_POINTS = 4000;
  private static final int MAX_SEGMENT_UTF8_BYTES = 16_384;
  private static final int MAX_TOTAL_SEGMENT_UTF8_BYTES = 384 * 1024;
  private static final int MAX_XML_DEPTH = 128;
  private static final String LEGACY_PARSER_VERSION = "epub3-v1";
  public static final String PARSER_VERSION = "epub3-v2";
  private static final Set<String> SEGMENT_TAGS =
      Set.of("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre");
  private static final Set<String> IGNORED_TAGS =
      Set.of("script", "style", "noscript", "svg", "math", "iframe", "object", "audio", "video");

  private final ImportContentValidator contentValidator;
  private final ImportStagingProperties properties;

  public EpubPackageParser(
      ImportContentValidator contentValidator, ImportStagingProperties properties) {
    this.contentValidator = contentValidator;
    this.properties = properties;
  }

  /**
   * Parse a previously uploaded EPUB into a bounded private preview. This method performs no DB or
   * file writes.
   */
  public Preview parse(Path file, String filename, String declaredContentType) throws IOException {
    return parse(file, filename, declaredContentType, PARSER_VERSION);
  }

  /** Retains deterministic behavior for durable jobs queued by an earlier parser version. */
  public Preview parse(Path file, String filename, String declaredContentType, String parserVersion)
      throws IOException {
    if (!PARSER_VERSION.equals(parserVersion) && !LEGACY_PARSER_VERSION.equals(parserVersion)) {
      throw failure(
          "EPUB_PARSER_VERSION_UNSUPPORTED",
          "The queued EPUB parser version is no longer supported");
    }
    contentValidator.validate(file, filename, declaredContentType);
    long deadline = deadline();
    long[] expandedBytes = {0};
    try (ZipFile archive = new ZipFile(file.toFile(), java.nio.charset.StandardCharsets.UTF_8)) {
      Map<String, ZipEntry> entries = indexEntries(archive, deadline);
      String packagePath = findPackagePath(archive, entries, expandedBytes, deadline);
      PackageDocument packageDocument =
          readPackageDocument(archive, entries, packagePath, expandedBytes, deadline);
      NavDocument navigation =
          readNavigation(archive, entries, packageDocument, expandedBytes, deadline);
      return buildPreview(
          archive, entries, packageDocument, navigation, expandedBytes, deadline, parserVersion);
    } catch (EpubParseException exception) {
      throw exception;
    } catch (XMLStreamException | IllegalArgumentException exception) {
      Throwable cause = exception.getCause();
      while (cause != null) {
        if (cause instanceof EpubParseException parseException) throw parseException;
        cause = cause.getCause();
      }
      throw new EpubParseException("EPUB_XML_INVALID", "EPUB package XML is malformed", exception);
    }
  }

  private Map<String, ZipEntry> indexEntries(ZipFile archive, long deadline)
      throws IOException, EpubParseException {
    LinkedHashMap<String, ZipEntry> entries = new LinkedHashMap<>();
    Set<String> canonicalNames = new HashSet<>();
    Enumeration<? extends ZipEntry> enumeration = archive.entries();
    ZipEntry first = enumeration.hasMoreElements() ? enumeration.nextElement() : null;
    if (first == null
        || !"mimetype".equals(first.getName())
        || first.isDirectory()
        || first.getMethod() != ZipEntry.STORED
        || first.getExtra() != null && first.getExtra().length > 0) {
      throw failure("EPUB_MIMETYPE_INVALID", "EPUB mimetype must be the first stored entry");
    }
    entries.put(first.getName(), first);
    canonicalNames.add(canonicalArchivePath(first.getName()));
    int count = 1;
    while (enumeration.hasMoreElements()) {
      checkDeadline(deadline);
      ZipEntry entry = enumeration.nextElement();
      if (++count > properties.maxArchiveEntries()) {
        throw failure("EPUB_ENTRY_LIMIT_EXCEEDED", "EPUB contains too many archive entries");
      }
      validateArchivePath(entry.getName(), entry.isDirectory());
      String canonical = canonicalArchivePath(entry.getName());
      if (!canonicalNames.add(canonical)) {
        throw failure("EPUB_DUPLICATE_PATH", "EPUB contains duplicate archive paths");
      }
      if (entry.getSize() < 0
          || entry.getCompressedSize() < 0
          || entry.getSize() > properties.maxArchiveEntryBytes()) {
        throw failure("EPUB_ENTRY_SIZE_INVALID", "EPUB entry size is missing or exceeds its limit");
      }
      if (entry.getSize() > 0
          && (entry.getCompressedSize() == 0
              || (double) entry.getSize() / entry.getCompressedSize()
                  > properties.maxArchiveCompressionRatio())) {
        throw failure(
            "EPUB_COMPRESSION_RATIO_EXCEEDED", "EPUB entry exceeds its compression-ratio limit");
      }
      if (entry.getMethod() != ZipEntry.STORED && entry.getMethod() != ZipEntry.DEFLATED) {
        throw failure(
            "EPUB_COMPRESSION_UNSUPPORTED", "EPUB uses an unsupported compression method");
      }
      entries.put(entry.getName(), entry);
    }
    if (entries.size() < 2 || !entries.containsKey(CONTAINER_PATH)) {
      throw failure("EPUB_CONTAINER_MISSING", "EPUB is missing META-INF/container.xml");
    }
    String mime = readSmallUtf8(archive, first, 64, deadline);
    if (!EPUB_MIMETYPE.equals(mime)) {
      throw failure("EPUB_MIMETYPE_INVALID", "EPUB mimetype content is invalid");
    }
    return Collections.unmodifiableMap(entries);
  }

  private String findPackagePath(
      ZipFile archive, Map<String, ZipEntry> entries, long[] expandedBytes, long deadline)
      throws IOException, XMLStreamException {
    ZipEntry container = entries.get(CONTAINER_PATH);
    if (container == null || container.isDirectory()) {
      throw failure("EPUB_CONTAINER_MISSING", "EPUB is missing META-INF/container.xml");
    }
    XmlSource source = openXml(archive, container, expandedBytes, deadline);
    XMLStreamReader xml = source.reader();
    List<Rootfile> rootfiles = new ArrayList<>();
    int depth = 0;
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        checkXmlEvent(xml, event, deadline);
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (++depth > MAX_XML_DEPTH)
            throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XML is nested too deeply");
          if (local(xml, "rootfile")) {
            String fullPath = attribute(xml, "full-path");
            String mediaType = attribute(xml, "media-type");
            if (fullPath != null) rootfiles.add(new Rootfile(fullPath, mediaType));
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          depth--;
        }
      }
    } finally {
      source.close();
    }
    List<Rootfile> preferred =
        rootfiles.stream().filter(root -> PACKAGE_MIME.equals(root.mediaType())).toList();
    List<Rootfile> candidates = preferred.isEmpty() ? rootfiles : preferred;
    if (candidates.size() != 1) {
      throw failure(
          "EPUB_PACKAGE_DOCUMENT_INVALID", "EPUB must identify exactly one package document");
    }
    String path = resolvePath("", candidates.getFirst().fullPath());
    if (path.isBlank() || !entries.containsKey(path) || entries.get(path).isDirectory()) {
      throw failure("EPUB_PACKAGE_DOCUMENT_MISSING", "EPUB package document is missing");
    }
    return path;
  }

  private PackageDocument readPackageDocument(
      ZipFile archive,
      Map<String, ZipEntry> entries,
      String packagePath,
      long[] expandedBytes,
      long deadline)
      throws IOException, XMLStreamException {
    XmlSource source = openXml(archive, entries.get(packagePath), expandedBytes, deadline);
    XMLStreamReader xml = source.reader();
    String title = null;
    String language = null;
    String identifier = null;
    String description = null;
    String publisher = null;
    String uniqueIdentifierRef = null;
    String uniqueIdentifier = null;
    List<String> creators = new ArrayList<>();
    Map<String, ManifestItem> manifest = new LinkedHashMap<>();
    Set<String> manifestPaths = new HashSet<>();
    List<SpineRef> spine = new ArrayList<>();
    String version = null;
    String navItemId = null;
    boolean packageRootSeen = false;
    int depth = 0;
    Deque<String> elementStack = new ArrayDeque<>();
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        checkXmlEvent(xml, event, deadline);
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (++depth > MAX_XML_DEPTH)
            throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XML is nested too deeply");
          String name = xml.getLocalName();
          String parentName = elementStack.peek();
          boolean consumed = false;
          if (!packageRootSeen) {
            packageRootSeen = true;
            if (!"package".equals(name))
              throw failure("EPUB_PACKAGE_DOCUMENT_INVALID", "EPUB package root is invalid");
            version = attribute(xml, "version");
            uniqueIdentifierRef = attribute(xml, "unique-identifier");
          } else if ("item".equals(name) && "manifest".equals(parentName)) {
            String id = attribute(xml, "id");
            String href = attribute(xml, "href");
            String mediaType = attribute(xml, "media-type");
            String itemProperties = attribute(xml, "properties");
            if (id == null || id.isBlank() || href == null || mediaType == null) {
              throw failure(
                  "EPUB_MANIFEST_INVALID", "EPUB manifest item is missing required attributes");
            }
            String itemPath = resolvePath(parentPath(packagePath), href);
            if (!manifestPaths.add(itemPath)) {
              throw failure(
                  "EPUB_MANIFEST_INVALID", "EPUB manifest maps multiple items to one path");
            }
            ManifestItem item = new ManifestItem(id, itemPath, mediaType, tokens(itemProperties));
            if (manifest.putIfAbsent(id, item) != null) {
              throw failure(
                  "EPUB_MANIFEST_INVALID", "EPUB manifest contains duplicate item identifiers");
            }
            if (item.properties().contains("nav")) {
              if (navItemId != null)
                throw failure(
                    "EPUB_NAV_AMBIGUOUS", "EPUB declares more than one navigation document");
              navItemId = id;
            }
          } else if ("itemref".equals(name) && "spine".equals(parentName)) {
            String idref = attribute(xml, "idref");
            if (idref == null || idref.isBlank())
              throw failure("EPUB_SPINE_INVALID", "EPUB spine item is missing idref");
            spine.add(new SpineRef(idref, !"no".equalsIgnoreCase(attribute(xml, "linear"))));
          } else if ("metadata".equals(parentName) && isDcElement(xml, "title")) {
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            if (title == null) title = value;
            depth--;
            consumed = true;
          } else if ("metadata".equals(parentName) && isDcElement(xml, "language")) {
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            if (language == null) language = value;
            depth--;
            consumed = true;
          } else if ("metadata".equals(parentName) && isDcElement(xml, "identifier")) {
            String identifierId = attribute(xml, "id");
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            if (identifier == null
                && uniqueIdentifierRef != null
                && uniqueIdentifierRef.equals(identifierId)) {
              identifier = value;
              uniqueIdentifier = identifierId;
            }
            depth--;
            consumed = true;
          } else if ("metadata".equals(parentName) && isDcElement(xml, "description")) {
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            if (description == null) description = value;
            depth--;
            consumed = true;
          } else if ("metadata".equals(parentName) && isDcElement(xml, "publisher")) {
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            if (publisher == null) publisher = value;
            depth--;
            consumed = true;
          } else if ("metadata".equals(parentName) && isDcElement(xml, "creator")) {
            String value = readElementText(xml, METADATA_TEXT_LIMIT, deadline);
            addDistinctBounded(creators, value, 20);
            depth--;
            consumed = true;
          }
          if (!consumed) elementStack.push(name);
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          if (!elementStack.isEmpty()) elementStack.pop();
          depth--;
        }
      }
    } finally {
      source.close();
    }
    if (!"3.0".equals(version)) {
      throw failure("EPUB_VERSION_UNSUPPORTED", "Only EPUB 3.0 package documents are supported");
    }
    if (title == null
        || title.isBlank()
        || identifier == null
        || identifier.isBlank()
        || uniqueIdentifier == null
        || uniqueIdentifier.isBlank()) {
      throw failure(
          "EPUB_METADATA_REQUIRED", "EPUB package metadata requires a title and identifier");
    }
    if (spine.isEmpty())
      throw failure("EPUB_SPINE_EMPTY", "EPUB package has no reading-order entries");
    if (spine.size() > MAX_PREVIEW_CHAPTERS)
      throw failure("EPUB_SPINE_LIMIT_EXCEEDED", "EPUB contains too many spine entries");
    for (ManifestItem item : manifest.values()) {
      if (!entries.containsKey(item.path()) && item.id().equals(navItemId)) {
        throw failure("EPUB_NAV_MISSING", "EPUB navigation document is missing");
      }
    }
    return new PackageDocument(
        new Metadata(title, language, identifier, publisher, description, List.copyOf(creators)),
        Collections.unmodifiableMap(manifest),
        List.copyOf(spine),
        navItemId);
  }

  private NavDocument readNavigation(
      ZipFile archive,
      Map<String, ZipEntry> entries,
      PackageDocument document,
      long[] expandedBytes,
      long deadline)
      throws IOException, XMLStreamException {
    if (document.navItemId() == null) return new NavDocument(null, List.of());
    ManifestItem navItem = document.manifest().get(document.navItemId());
    ZipEntry entry = entries.get(navItem.path());
    if (entry == null || entry.isDirectory())
      throw failure("EPUB_NAV_MISSING", "EPUB navigation document is missing");
    if (!XHTML_MIME.equals(navItem.mediaType())) {
      throw failure("EPUB_NAV_UNSUPPORTED", "EPUB navigation document must be XHTML");
    }

    XmlSource source = openXml(archive, entry, expandedBytes, deadline);
    XMLStreamReader xml = source.reader();
    int tocNavDepth = 0;
    Deque<TocFrame> items = new ArrayDeque<>();
    List<TocReference> references = new ArrayList<>();
    long order = 0;
    int depth = 0;
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        checkXmlEvent(xml, event, deadline);
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (++depth > MAX_XML_DEPTH)
            throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XML is nested too deeply");
          String name = xml.getLocalName();
          if ("nav".equals(name)
              && tocNavDepth == 0
              && hasToken(attribute(xml, EPUB_NAMESPACE, "type"), "toc")) {
            tocNavDepth = 1;
          } else if (tocNavDepth > 0 && "nav".equals(name)) {
            tocNavDepth++;
          } else if (tocNavDepth > 0 && "li".equals(name)) {
            items.push(new TocFrame(items.size()));
          } else if (tocNavDepth > 0
              && !items.isEmpty()
              && ("a".equals(name) || "span".equals(name))) {
            String href = "a".equals(name) ? attribute(xml, "href") : null;
            String label = readElementText(xml, NAV_LABEL_LIMIT, deadline);
            if (!label.isBlank() && items.peek().label().isBlank()) {
              items.peek().set(label, href, order++);
            }
            depth--;
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String name = xml.getLocalName();
          if (tocNavDepth > 0 && "li".equals(name) && !items.isEmpty()) {
            TocFrame frame = items.pop();
            if (!frame.label().isBlank()) {
              if (references.size() >= MAX_PREVIEW_TOC_ENTRIES) {
                throw failure(
                    "EPUB_TOC_LIMIT_EXCEEDED", "EPUB navigation contains too many entries");
              }
              references.add(
                  new TocReference(frame.order(), frame.depth(), frame.label(), frame.href()));
            }
          } else if (tocNavDepth > 0 && "nav".equals(name)) {
            tocNavDepth--;
          }
          depth--;
        }
      }
    } finally {
      source.close();
    }
    references.sort(Comparator.comparingLong(TocReference::order));
    if (references.size() > MAX_PREVIEW_TOC_ENTRIES) {
      throw failure("EPUB_TOC_LIMIT_EXCEEDED", "EPUB navigation contains too many entries");
    }
    return new NavDocument(navItem.path(), List.copyOf(references));
  }

  private Preview buildPreview(
      ZipFile archive,
      Map<String, ZipEntry> entries,
      PackageDocument document,
      NavDocument navigation,
      long[] expandedBytes,
      long deadline,
      String parserVersion)
      throws IOException, XMLStreamException {
    List<Warning> warnings = new ArrayList<>();
    List<Chapter> chapters = new ArrayList<>();
    Map<String, Integer> chapterIndices = new HashMap<>();
    SegmentBudget segmentBudget = new SegmentBudget();
    for (int index = 0; index < document.spine().size(); index++) {
      checkDeadline(deadline);
      SpineRef reference = document.spine().get(index);
      ManifestItem item = document.manifest().get(reference.idref());
      if (item == null)
        throw failure("EPUB_SPINE_INVALID", "EPUB spine references an unknown manifest item");
      ZipEntry entry = entries.get(item.path());
      if (entry == null || entry.isDirectory()) {
        throw failure("EPUB_SPINE_RESOURCE_MISSING", "EPUB spine references a missing resource");
      }
      if (!XHTML_MIME.equals(item.mediaType())) {
        warnings.add(
            new Warning(
                "EPUB_SPINE_ITEM_UNSUPPORTED",
                "A non-XHTML spine resource was retained as an empty preview chapter."));
        Chapter chapter =
            new Chapter(
                index,
                item.path(),
                "Unsupported content",
                reference.linear(),
                0,
                "",
                true,
                "",
                "",
                List.of());
        chapterIndices.put(item.path(), index);
        chapters.add(chapter);
        continue;
      }
      ChapterText chapterText =
          LEGACY_PARSER_VERSION.equals(parserVersion)
              ? readChapterTextV1(archive, entry, expandedBytes, deadline)
              : readChapterText(archive, entry, expandedBytes, deadline, segmentBudget);
      if (chapterText.scripted()) {
        warnings.add(
            new Warning(
                "EPUB_SCRIPT_IGNORED", "Scripted EPUB content is ignored in the text preview."));
      }
      if (chapterText.segmentCount() == 0) {
        warnings.add(new Warning("EPUB_CHAPTER_EMPTY", "A spine item contains no readable text."));
      }
      chapterIndices.put(item.path(), index);
      chapters.add(
          new Chapter(
              index,
              item.path(),
              null,
              reference.linear(),
              chapterText.segmentCount(),
              chapterText.preview(),
              chapterText.segmentCount() == 0,
              chapterText.documentTitle(),
              chapterText.firstHeading(),
              chapterText.segments()));
    }
    if (chapters.stream().noneMatch(chapter -> !chapter.empty())) {
      throw failure("EPUB_NO_READABLE_CHAPTERS", "EPUB has no readable XHTML chapter content");
    }

    List<TocEntry> toc = new ArrayList<>();
    Map<String, String> tocTitles = new HashMap<>();
    if (navigation.references().isEmpty()) {
      warnings.add(
          new Warning(
              "EPUB_TOC_MISSING",
              "No usable EPUB navigation table was found; spine order is used."));
    } else {
      for (TocReference reference : navigation.references()) {
        checkDeadline(deadline);
        if (reference.href() == null || reference.href().isBlank()) continue;
        ResolvedReference resolved;
        try {
          resolved = resolveReference(navigation.path(), reference.href());
        } catch (EpubParseException exception) {
          warnings.add(
              new Warning(
                  "EPUB_TOC_TARGET_UNRESOLVED", "A navigation link points outside the package."));
          continue;
        }
        Integer chapterIndex = chapterIndices.get(resolved.path());
        if (chapterIndex == null) {
          warnings.add(
              new Warning(
                  "EPUB_TOC_TARGET_UNRESOLVED",
                  "A navigation link does not point to a spine item."));
          continue;
        }
        toc.add(
            new TocEntry(reference.label(), reference.depth(), chapterIndex, resolved.fragment()));
        tocTitles.putIfAbsent(resolved.path(), reference.label());
      }
    }
    for (int i = 0; i < chapters.size(); i++) {
      Chapter chapter = chapters.get(i);
      String chapterTitle =
          firstNonBlank(
              tocTitles.get(chapter.sourcePath()),
              chapter.firstHeading(),
              chapter.documentTitle(),
              "Chapter " + (i + 1));
      chapters.set(i, chapter.withTitle(chapterTitle));
    }
    if (toc.isEmpty()) {
      for (Chapter chapter : chapters) {
        toc.add(new TocEntry(chapter.title(), 0, chapter.index(), null));
      }
    }
    if (entries.containsKey("META-INF/encryption.xml")) {
      warnings.add(
          new Warning(
              "EPUB_ENCRYPTED_RESOURCES_UNSUPPORTED",
              "Encrypted fonts or other encrypted EPUB resources are not processed."));
    }
    return new Preview(
        parserVersion,
        document.metadata(),
        List.copyOf(chapters),
        List.copyOf(toc),
        List.copyOf(warnings));
  }

  private ChapterText readChapterTextV1(
      ZipFile archive, ZipEntry entry, long[] expandedBytes, long deadline)
      throws IOException, XMLStreamException {
    XmlSource source = openXml(archive, entry, expandedBytes, deadline);
    XMLStreamReader xml = source.reader();
    boolean inBody = false;
    int ignoredDepth = 0;
    int depth = 0;
    StringBuilder documentTitle = new StringBuilder();
    boolean inTitle = false;
    Deque<LegacySegmentFrame> blocks = new ArrayDeque<>();
    LegacySegmentFrame looseText = new LegacySegmentFrame("#text");
    int segmentCount = 0;
    String preview = "";
    String firstHeading = "";
    boolean scripted = false;
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        checkXmlEvent(xml, event, deadline);
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (++depth > MAX_XML_DEPTH)
            throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XHTML is nested too deeply");
          String name = xml.getLocalName().toLowerCase(Locale.ROOT);
          if (ignoredDepth > 0) {
            ignoredDepth++;
            continue;
          }
          if (IGNORED_TAGS.contains(name)) {
            if ("script".equals(name)) scripted = true;
            ignoredDepth = 1;
            continue;
          }
          if (!inBody && "title".equals(name)) {
            inTitle = true;
          } else if ("body".equals(name)) {
            inBody = true;
          } else if (inBody && SEGMENT_TAGS.contains(name)) {
            if (!blocks.isEmpty()) {
              LegacySegmentFrame prior = blocks.peek();
              if (prior.hasText()) {
                segmentCount++;
                if (preview.isBlank()) preview = prior.preview();
                prior.clear();
              }
            } else if (looseText.hasText()) {
              segmentCount++;
              if (preview.isBlank()) preview = looseText.preview();
              looseText = new LegacySegmentFrame("#text");
            }
            blocks.push(new LegacySegmentFrame(name));
          }
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (ignoredDepth > 0) continue;
          if (inTitle) appendBounded(documentTitle, xml.getText(), NAV_LABEL_LIMIT);
          else if (inBody) {
            LegacySegmentFrame active = blocks.isEmpty() ? looseText : blocks.peek();
            active.append(xml.getText());
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String name = xml.getLocalName().toLowerCase(Locale.ROOT);
          if (ignoredDepth > 0) {
            ignoredDepth--;
            depth--;
            continue;
          }
          if (!inBody && "title".equals(name)) inTitle = false;
          if (inBody
              && SEGMENT_TAGS.contains(name)
              && !blocks.isEmpty()
              && blocks.peek().tag().equals(name)) {
            LegacySegmentFrame frame = blocks.pop();
            if (frame.hasText()) {
              segmentCount++;
              if (preview.isBlank()) preview = frame.preview();
              if (firstHeading.isBlank() && name.matches("h[1-6]")) firstHeading = frame.preview();
            }
          } else if ("body".equals(name)) {
            if (looseText.hasText()) {
              segmentCount++;
              if (preview.isBlank()) preview = looseText.preview();
              looseText = new LegacySegmentFrame("#text");
            }
            inBody = false;
          }
          depth--;
        }
      }
    } finally {
      source.close();
    }
    while (!blocks.isEmpty()) {
      LegacySegmentFrame frame = blocks.removeLast();
      if (frame.hasText()) {
        segmentCount++;
        if (preview.isBlank()) preview = frame.preview();
        if (firstHeading.isBlank() && frame.tag().matches("h[1-6]")) firstHeading = frame.preview();
      }
    }
    if (looseText.hasText()) {
      segmentCount++;
      if (preview.isBlank()) preview = looseText.preview();
    }
    return new ChapterText(
        segmentCount,
        preview,
        normalize(documentTitle.toString()),
        firstHeading,
        scripted,
        List.of());
  }

  private ChapterText readChapterText(
      ZipFile archive,
      ZipEntry entry,
      long[] expandedBytes,
      long deadline,
      SegmentBudget segmentBudget)
      throws IOException, XMLStreamException {
    XmlSource source = openXml(archive, entry, expandedBytes, deadline);
    XMLStreamReader xml = source.reader();
    boolean inBody = false;
    int ignoredDepth = 0;
    int depth = 0;
    StringBuilder documentTitle = new StringBuilder();
    boolean inTitle = false;
    Deque<SegmentFrame> blocks = new ArrayDeque<>();
    SegmentFrame looseText = new SegmentFrame("#text");
    List<SourceSegment> segments = new ArrayList<>();
    String preview = "";
    String firstHeading = "";
    boolean scripted = false;
    try {
      while (xml.hasNext()) {
        int event = xml.next();
        checkXmlEvent(xml, event, deadline);
        if (event == XMLStreamConstants.START_ELEMENT) {
          if (++depth > MAX_XML_DEPTH)
            throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XHTML is nested too deeply");
          String name = xml.getLocalName().toLowerCase(Locale.ROOT);
          if (ignoredDepth > 0) {
            ignoredDepth++;
            continue;
          }
          if (IGNORED_TAGS.contains(name)) {
            if ("script".equals(name)) scripted = true;
            ignoredDepth = 1;
            continue;
          }
          if (!inBody && "title".equals(name)) {
            inTitle = true;
          } else if ("body".equals(name)) {
            inBody = true;
          } else if (inBody && SEGMENT_TAGS.contains(name)) {
            if (!blocks.isEmpty()) {
              SegmentFrame prior = blocks.peek();
              if (prior.hasText()) {
                SourceSegment segment = addSegment(prior, segments, segmentBudget);
                if (preview.isBlank()) preview = previewText(segment.text());
                prior.clear();
              }
            } else if (looseText.hasText()) {
              SourceSegment segment = addSegment(looseText, segments, segmentBudget);
              if (preview.isBlank()) preview = previewText(segment.text());
              looseText = new SegmentFrame("#text");
            }
            blocks.push(new SegmentFrame(name));
          }
        } else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
          if (ignoredDepth > 0) continue;
          if (inTitle) appendBounded(documentTitle, xml.getText(), NAV_LABEL_LIMIT);
          else if (inBody) {
            SegmentFrame active = blocks.isEmpty() ? looseText : blocks.peek();
            active.append(xml.getText());
          }
        } else if (event == XMLStreamConstants.END_ELEMENT) {
          String name = xml.getLocalName().toLowerCase(Locale.ROOT);
          if (ignoredDepth > 0) {
            ignoredDepth--;
            depth--;
            continue;
          }
          if (!inBody && "title".equals(name)) inTitle = false;
          if (inBody
              && SEGMENT_TAGS.contains(name)
              && !blocks.isEmpty()
              && blocks.peek().tag().equals(name)) {
            SegmentFrame frame = blocks.pop();
            if (frame.hasText()) {
              SourceSegment segment = addSegment(frame, segments, segmentBudget);
              if (preview.isBlank()) preview = previewText(segment.text());
              if (firstHeading.isBlank() && name.matches("h[1-6]"))
                firstHeading = previewText(segment.text());
            }
          } else if ("body".equals(name)) {
            if (looseText.hasText()) {
              SourceSegment segment = addSegment(looseText, segments, segmentBudget);
              if (preview.isBlank()) preview = previewText(segment.text());
              looseText = new SegmentFrame("#text");
            }
            inBody = false;
          }
          depth--;
        }
      }
    } finally {
      source.close();
    }
    while (!blocks.isEmpty()) {
      SegmentFrame frame = blocks.removeLast();
      if (frame.hasText()) {
        SourceSegment segment = addSegment(frame, segments, segmentBudget);
        if (preview.isBlank()) preview = previewText(segment.text());
        if (firstHeading.isBlank() && frame.tag().matches("h[1-6]"))
          firstHeading = previewText(segment.text());
      }
    }
    if (looseText.hasText()) {
      SourceSegment segment = addSegment(looseText, segments, segmentBudget);
      if (preview.isBlank()) preview = previewText(segment.text());
    }
    return new ChapterText(
        segments.size(),
        preview,
        normalize(documentTitle.toString()),
        firstHeading,
        scripted,
        List.copyOf(segments));
  }

  private SourceSegment addSegment(
      SegmentFrame frame, List<SourceSegment> segments, SegmentBudget budget)
      throws EpubParseException {
    String text = normalize(frame.text());
    if (text.isEmpty()) return null;
    int bytes = text.getBytes(StandardCharsets.UTF_8).length;
    if (text.codePointCount(0, text.length()) > MAX_SEGMENT_CODE_POINTS
        || bytes > MAX_SEGMENT_UTF8_BYTES) {
      throw failure(
          "EPUB_SEGMENT_TOO_LARGE", "An EPUB text segment exceeds its normalized size limit");
    }
    budget.addSegment(bytes);
    SourceSegment segment =
        new SourceSegment(
            segments.size(), "#text".equals(frame.tag()) ? "text" : frame.tag(), text);
    segments.add(segment);
    return segment;
  }

  private static String previewText(String text) {
    int length = text.codePointCount(0, text.length());
    return length <= PREVIEW_TEXT_LIMIT
        ? text
        : text.substring(0, text.offsetByCodePoints(0, PREVIEW_TEXT_LIMIT));
  }

  private XmlSource openXml(ZipFile archive, ZipEntry entry, long[] expandedBytes, long deadline)
      throws IOException, XMLStreamException {
    if (entry.getSize() > properties.maxArchiveEntryBytes()) {
      throw failure("EPUB_ENTRY_LIMIT_EXCEEDED", "EPUB XML entry exceeds its size limit");
    }
    InputStream input =
        new LimitedEntryInputStream(
            archive.getInputStream(entry),
            properties.maxArchiveEntryBytes(),
            properties.maxArchiveExpandedBytes(),
            expandedBytes,
            deadline);
    try {
      XMLInputFactory factory = secureFactory();
      XMLStreamReader reader = factory.createXMLStreamReader(input);
      String encoding = reader.getEncoding();
      if (encoding != null
          && !encoding.toUpperCase(Locale.ROOT).startsWith("UTF-8")
          && !encoding.toUpperCase(Locale.ROOT).startsWith("UTF-16")) {
        reader.close();
        throw failure("EPUB_ENCODING_UNSUPPORTED", "EPUB XML must use UTF-8 or UTF-16 encoding");
      }
      return new XmlSource(reader, input);
    } catch (IOException | XMLStreamException | RuntimeException exception) {
      try {
        input.close();
      } catch (IOException closeException) {
        exception.addSuppressed(closeException);
      }
      throw exception;
    }
  }

  private static XMLInputFactory secureFactory() throws XMLStreamException {
    XMLInputFactory factory = XMLInputFactory.newFactory();
    setRequired(factory, XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    setRequired(factory, XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    setRequired(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, Boolean.TRUE);
    factory.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
    try {
      factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    } catch (IllegalArgumentException ignored) {
      // DTD and external entities are disabled above; providers may not expose this optional
      // property.
    }
    factory.setXMLResolver(
        (publicID, systemID, baseURI, namespace) -> {
          throw new XMLStreamException("External XML resources are disabled");
        });
    return factory;
  }

  private static void setRequired(XMLInputFactory factory, String property, Object value)
      throws XMLStreamException {
    try {
      factory.setProperty(property, value);
      if (!value.equals(factory.getProperty(property))) {
        throw new XMLStreamException("XML provider did not apply a required security setting");
      }
    } catch (IllegalArgumentException exception) {
      throw new XMLStreamException(
          "XML provider does not support a required security setting", exception);
    }
  }

  private static void checkXmlEvent(XMLStreamReader xml, int event, long deadline)
      throws XMLStreamException, EpubParseException {
    if (System.nanoTime() - deadline >= 0) {
      throw failure("EPUB_PARSE_TIMEOUT", "EPUB parsing exceeded its time limit");
    }
    if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
      throw failure(
          "EPUB_EXTERNAL_ENTITY_BLOCKED",
          "DTD and entity references are not supported in EPUB XML");
    }
  }

  private static String readElementText(XMLStreamReader xml, int maxChars, long deadline)
      throws XMLStreamException, EpubParseException {
    StringBuilder value = new StringBuilder(Math.min(maxChars, 256));
    int nested = 1;
    while (nested > 0 && xml.hasNext()) {
      int event = xml.next();
      checkXmlEvent(xml, event, deadline);
      if (event == XMLStreamConstants.START_ELEMENT) {
        if (++nested > MAX_XML_DEPTH)
          throw failure("EPUB_XML_DEPTH_EXCEEDED", "EPUB XML is nested too deeply");
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        nested--;
      } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
          && value.length() < maxChars) {
        appendBounded(value, xml.getText(), maxChars);
      }
    }
    if (nested != 0) throw failure("EPUB_XML_INVALID", "EPUB XML element is incomplete");
    return normalize(value.toString());
  }

  private String readSmallUtf8(ZipFile archive, ZipEntry entry, int maxBytes, long deadline)
      throws IOException, EpubParseException {
    if (entry.getSize() > maxBytes)
      throw failure("EPUB_MIMETYPE_INVALID", "EPUB mimetype content is invalid");
    try (InputStream input = archive.getInputStream(entry)) {
      byte[] bytes = input.readNBytes(maxBytes + 1);
      if (bytes.length != entry.getSize() || bytes.length > maxBytes) {
        throw failure("EPUB_MIMETYPE_INVALID", "EPUB mimetype content is invalid");
      }
      if (System.nanoTime() - deadline >= 0)
        throw failure("EPUB_PARSE_TIMEOUT", "EPUB parsing exceeded its time limit");
      return new String(bytes, java.nio.charset.StandardCharsets.US_ASCII);
    }
  }

  private static boolean isDcElement(XMLStreamReader xml, String localName) {
    return localName(xml, localName) && DC_NAMESPACE.equals(xml.getNamespaceURI());
  }

  private static boolean localName(XMLStreamReader xml, String name) {
    return name.equals(xml.getLocalName());
  }

  private static boolean local(XMLStreamReader xml, String name) {
    return name.equals(xml.getLocalName());
  }

  private static String attribute(XMLStreamReader xml, String name) {
    return attribute(xml, null, name);
  }

  private static String attribute(XMLStreamReader xml, String namespace, String name) {
    for (int index = 0; index < xml.getAttributeCount(); index++) {
      String attributeNamespace = xml.getAttributeNamespace(index);
      if (name.equals(xml.getAttributeLocalName(index))
          && (namespace == null
              ? attributeNamespace == null || attributeNamespace.isEmpty()
              : namespace.equals(attributeNamespace))) {
        return xml.getAttributeValue(index);
      }
    }
    return null;
  }

  private static Set<String> tokens(String value) {
    if (value == null || value.isBlank()) return Set.of();
    Set<String> result = new HashSet<>();
    for (String token : value.trim().split("\\s+")) result.add(token);
    return Set.copyOf(result);
  }

  private static boolean hasToken(String value, String expected) {
    return tokens(value).contains(expected);
  }

  private static void addDistinctBounded(List<String> target, String value, int limit) {
    if (!value.isBlank() && target.size() < limit && !target.contains(value)) target.add(value);
  }

  private static String firstNonBlank(String... values) {
    for (String value : values) if (value != null && !value.isBlank()) return value;
    return "";
  }

  private static void appendBounded(StringBuilder target, CharSequence value, int limit) {
    int remaining = limit - target.length();
    if (remaining > 0) target.append(value, 0, Math.min(remaining, value.length()));
  }

  private static String normalize(String value) {
    String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).replace('\u00a0', ' ');
    return normalized.replaceAll("[\\p{Z}\\s]+", " ").trim();
  }

  private static String parentPath(String path) {
    int slash = path.lastIndexOf('/');
    return slash < 0 ? "" : path.substring(0, slash + 1);
  }

  private static String resolvePath(String baseDirectory, String reference)
      throws EpubParseException {
    if (reference == null || reference.isBlank())
      throw failure("EPUB_PATH_INVALID", "EPUB resource path is empty");
    try {
      URI uri = new URI(reference);
      if (uri.isAbsolute()
          || uri.getRawAuthority() != null
          || uri.getRawQuery() != null
          || uri.getRawFragment() != null) {
        throw failure(
            "EPUB_EXTERNAL_RESOURCE_UNSUPPORTED", "External EPUB resources are not fetched");
      }
      String decoded = uri.getPath();
      if (decoded == null
          || decoded.isBlank()
          || decoded.startsWith("/")
          || decoded.startsWith("\\")
          || decoded.contains("\\")
          || decoded.matches("^[A-Za-z]:.*")) {
        throw failure("EPUB_PATH_INVALID", "EPUB resource path is unsafe");
      }
      Deque<String> segments = new ArrayDeque<>();
      if (baseDirectory != null && !baseDirectory.isBlank()) {
        for (String segment : baseDirectory.split("/"))
          if (!segment.isBlank()) segments.addLast(segment);
      }
      for (String segment : decoded.split("/", -1)) {
        if (segment.isEmpty() || segment.equals(".")) continue;
        if (segment.equals("..")) {
          if (segments.isEmpty())
            throw failure("EPUB_PATH_INVALID", "EPUB resource path escapes the package");
          segments.removeLast();
        } else {
          if (segment.endsWith(".")
              || segment.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 255) {
            throw failure("EPUB_PATH_INVALID", "EPUB resource path is unsafe");
          }
          segments.addLast(segment);
        }
      }
      if (segments.isEmpty() || segments.size() > 32)
        throw failure("EPUB_PATH_INVALID", "EPUB resource path is unsafe");
      return String.join("/", segments);
    } catch (URISyntaxException exception) {
      throw new EpubParseException(
          "EPUB_PATH_INVALID", "EPUB resource path is malformed", exception);
    }
  }

  private static ResolvedReference resolveReference(String documentPath, String reference)
      throws EpubParseException {
    try {
      URI uri = new URI(reference);
      if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawQuery() != null) {
        throw failure(
            "EPUB_EXTERNAL_RESOURCE_UNSUPPORTED", "External EPUB resources are not fetched");
      }
      String rawPath = uri.getRawPath();
      String path =
          rawPath == null || rawPath.isEmpty()
              ? documentPath
              : resolvePath(parentPath(documentPath), rawPath);
      return new ResolvedReference(path, uri.getFragment());
    } catch (URISyntaxException exception) {
      throw new EpubParseException(
          "EPUB_PATH_INVALID", "EPUB navigation reference is malformed", exception);
    }
  }

  private static void validateArchivePath(String name, boolean directory)
      throws EpubParseException {
    if (name == null
        || name.isBlank()
        || name.startsWith("/")
        || name.startsWith("\\")
        || name.contains("\\")
        || name.matches("^[A-Za-z]:.*")) {
      throw failure("EPUB_PATH_INVALID", "EPUB archive path is unsafe");
    }
    String path = directory && name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    String[] segments = path.split("/", -1);
    if (segments.length < 1 || segments.length > 32)
      throw failure("EPUB_PATH_INVALID", "EPUB archive path is too deep");
    for (String segment : segments) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.endsWith(".")) {
        throw failure("EPUB_PATH_INVALID", "EPUB archive path is unsafe");
      }
    }
  }

  private static String canonicalArchivePath(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .toUpperCase(Locale.ROOT)
        .toLowerCase(Locale.ROOT);
  }

  private long deadline() throws EpubParseException {
    long now = System.nanoTime();
    Duration timeout = properties.validationTimeout();
    try {
      return Math.addExact(now, timeout.toNanos());
    } catch (ArithmeticException exception) {
      return Long.MAX_VALUE;
    }
  }

  private static void checkDeadline(long deadline) throws EpubParseException {
    if (System.nanoTime() - deadline >= 0)
      throw failure("EPUB_PARSE_TIMEOUT", "EPUB parsing exceeded its time limit");
  }

  private static EpubParseException failure(String code, String message) {
    return new EpubParseException(code, message);
  }

  private final class LimitedEntryInputStream extends FilterInputStream {
    private final long entryLimit;
    private final long archiveLimit;
    private final long[] archiveBytes;
    private final long deadline;
    private long entryBytes;

    private LimitedEntryInputStream(
        InputStream input, long entryLimit, long archiveLimit, long[] archiveBytes, long deadline) {
      super(input);
      this.entryLimit = entryLimit;
      this.archiveLimit = archiveLimit;
      this.archiveBytes = archiveBytes;
      this.deadline = deadline;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value >= 0) account(1);
      return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
      int read = super.read(bytes, offset, length);
      if (read > 0) account(read);
      return read;
    }

    private void account(int read) throws IOException {
      if (System.nanoTime() - deadline >= 0)
        throw failure("EPUB_PARSE_TIMEOUT", "EPUB parsing exceeded its time limit");
      entryBytes += read;
      archiveBytes[0] += read;
      if (entryBytes > entryLimit)
        throw failure("EPUB_ENTRY_LIMIT_EXCEEDED", "EPUB XML entry exceeds its size limit");
      if (archiveBytes[0] > archiveLimit)
        throw failure(
            "EPUB_EXPANDED_LIMIT_EXCEEDED", "EPUB parsed content exceeds its expanded-byte limit");
    }
  }

  public record Metadata(
      String title,
      String language,
      String identifier,
      String publisher,
      String description,
      List<String> creators) {}

  public record Chapter(
      int index,
      String sourcePath,
      String title,
      boolean linear,
      int segmentCount,
      String textPreview,
      boolean empty,
      String documentTitle,
      String firstHeading,
      List<SourceSegment> segments) {
    public Chapter {
      segments = segments == null ? List.of() : List.copyOf(segments);
    }

    private Chapter withTitle(String value) {
      return new Chapter(
          index,
          sourcePath,
          value,
          linear,
          segmentCount,
          textPreview,
          empty,
          documentTitle,
          firstHeading,
          segments);
    }
  }

  public record SourceSegment(int ordinal, String kind, String text) {}

  public record TocEntry(String label, int depth, int chapterIndex, String fragment) {}

  public record Warning(String code, String summary) {}

  public record Preview(
      String parserVersion,
      Metadata metadata,
      List<Chapter> chapters,
      List<TocEntry> toc,
      List<Warning> warnings) {
    public Preview {
      chapters = chapters == null ? List.of() : List.copyOf(chapters);
      toc = toc == null ? List.of() : List.copyOf(toc);
      warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
  }

  public static final class EpubParseException extends IOException {
    private final String code;

    public EpubParseException(String code, String message) {
      super(message);
      this.code = code;
    }

    public EpubParseException(String code, String message, Throwable cause) {
      super(message, cause);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }

  private record Rootfile(String fullPath, String mediaType) {}

  private record ManifestItem(String id, String path, String mediaType, Set<String> properties) {}

  private record SpineRef(String idref, boolean linear) {}

  private record PackageDocument(
      Metadata metadata,
      Map<String, ManifestItem> manifest,
      List<SpineRef> spine,
      String navItemId) {}

  private record NavDocument(String path, List<TocReference> references) {}

  private record TocReference(long order, int depth, String label, String href) {}

  private record ResolvedReference(String path, String fragment) {}

  private record ChapterText(
      int segmentCount,
      String preview,
      String documentTitle,
      String firstHeading,
      boolean scripted,
      List<SourceSegment> segments) {}

  private static final class SegmentBudget {
    private int totalSegments;
    private int totalBytes;

    void addSegment(int bytes) throws EpubParseException {
      if (totalSegments >= MAX_SOURCE_SEGMENTS) {
        throw failure(
            "EPUB_SEGMENT_LIMIT_EXCEEDED", "EPUB contains too many readable text segments");
      }
      if (bytes > MAX_TOTAL_SEGMENT_UTF8_BYTES - totalBytes) {
        throw failure(
            "EPUB_SEGMENT_BYTES_LIMIT_EXCEEDED",
            "EPUB normalized source segments exceed their total size limit");
      }
      totalSegments++;
      totalBytes += bytes;
    }
  }

  private static final class XmlSource {
    private final XMLStreamReader reader;
    private final InputStream input;

    private XmlSource(XMLStreamReader reader, InputStream input) {
      this.reader = reader;
      this.input = input;
    }

    XMLStreamReader reader() {
      return reader;
    }

    void close() throws IOException {
      XMLStreamException streamFailure = null;
      try {
        reader.close();
      } catch (XMLStreamException exception) {
        streamFailure = exception;
      }
      try {
        input.close();
      } catch (IOException exception) {
        if (streamFailure != null) exception.addSuppressed(streamFailure);
        throw exception;
      }
      if (streamFailure != null)
        throw new IOException("Could not close EPUB XML reader", streamFailure);
    }
  }

  private static final class TocFrame {
    private final int depth;
    private String label = "";
    private String href;
    private long order;

    private TocFrame(int depth) {
      this.depth = depth;
    }

    int depth() {
      return depth;
    }

    String label() {
      return label;
    }

    String href() {
      return href;
    }

    long order() {
      return order;
    }

    void set(String nextLabel, String nextHref, long nextOrder) {
      label = nextLabel;
      href = nextHref;
      order = nextOrder;
    }
  }

  private static final class SegmentFrame {
    private final String tag;
    private final StringBuilder text = new StringBuilder(Math.min(MAX_SEGMENT_CODE_POINTS, 256));
    private boolean hasText;
    private boolean pendingSpace;
    private int codePoints;

    private SegmentFrame(String tag) {
      this.tag = tag;
    }

    String tag() {
      return tag;
    }

    boolean hasText() {
      return hasText;
    }

    String text() {
      return text.toString();
    }

    void clear() {
      text.setLength(0);
      hasText = false;
      pendingSpace = false;
      codePoints = 0;
    }

    void append(CharSequence value) throws EpubParseException {
      for (int offset = 0; offset < value.length(); ) {
        int codePoint = Character.codePointAt(value, offset);
        offset += Character.charCount(codePoint);
        if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
          if (hasText) pendingSpace = true;
        } else if (!Character.isISOControl(codePoint)) {
          hasText = true;
          int required = 1 + (pendingSpace ? 1 : 0);
          if (codePoints > MAX_SEGMENT_CODE_POINTS - required) {
            throw failure(
                "EPUB_SEGMENT_TOO_LARGE", "An EPUB text segment exceeds its normalized size limit");
          }
          if (pendingSpace) {
            text.append(' ');
            codePoints++;
            pendingSpace = false;
          }
          text.appendCodePoint(codePoint);
          codePoints++;
        }
      }
    }
  }

  private static final class LegacySegmentFrame {
    private final String tag;
    private final StringBuilder preview = new StringBuilder(PREVIEW_TEXT_LIMIT);
    private boolean hasText;
    private boolean lastSpace;

    private LegacySegmentFrame(String tag) {
      this.tag = tag;
    }

    String tag() {
      return tag;
    }

    boolean hasText() {
      return hasText;
    }

    String preview() {
      return preview.toString().trim();
    }

    void clear() {
      preview.setLength(0);
      hasText = false;
      lastSpace = false;
    }

    void append(CharSequence value) {
      for (int index = 0; index < value.length(); index++) {
        char character = value.charAt(index);
        if (Character.isWhitespace(character) || Character.isSpaceChar(character)) {
          if (hasText && !lastSpace && preview.length() < PREVIEW_TEXT_LIMIT) preview.append(' ');
          lastSpace = true;
        } else if (!Character.isISOControl(character)) {
          hasText = true;
          lastSpace = false;
          if (preview.length() < PREVIEW_TEXT_LIMIT) preview.append(character);
        }
      }
    }
  }
}
