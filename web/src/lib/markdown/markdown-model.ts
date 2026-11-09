// The Markdown <-> ProseMirror model behind the visual editor. Pure (no DOM), so it is unit
// tested in node. The server renders with pulldown-cmark (CommonMark + tables + strikethrough);
// this model covers CommonMark plus strikethrough. Anything it cannot carry without changing
// the rendered result (tables, ...) is detected by `canEditVisually` and stays raw.
import MarkdownIt from "markdown-it";
import {
  MarkdownParser,
  MarkdownSerializer,
  defaultMarkdownParser,
  defaultMarkdownSerializer,
  schema as baseSchema,
} from "prosemirror-markdown";
import { Schema, type Node as PmNode } from "prosemirror-model";

export const markdownSchema = new Schema({
  nodes: baseSchema.spec.nodes,
  marks: baseSchema.spec.marks.addToEnd("strike", {
    parseDOM: [{ tag: "s" }, { tag: "del" }, { tag: "strike" }],
    toDOM: () => ["s", 0],
  }),
});

function tokenizer(lines: boolean): ReturnType<typeof MarkdownIt> {
  return MarkdownIt("commonmark", { html: false, breaks: lines }).enable([
    "strikethrough",
  ]);
}

const parsers = new Map<boolean, MarkdownParser>();
const serializers = new Map<boolean, MarkdownSerializer>();

function parserFor(lines: boolean): MarkdownParser {
  let parser = parsers.get(lines);
  if (!parser) {
    const tokens = { ...defaultMarkdownParser.tokens, s: { mark: "strike" } };
    if (lines) {
      // A soft break of a line field is a real line break.
      Object.assign(tokens, { softbreak: { node: "hard_break" } });
    }
    parser = new MarkdownParser(markdownSchema, tokenizer(lines), tokens);
    parsers.set(lines, parser);
  }
  return parser;
}

function serializerFor(lines: boolean): MarkdownSerializer {
  let serializer = serializers.get(lines);
  if (!serializer) {
    const nodes = { ...defaultMarkdownSerializer.nodes };
    // Dash bullets, as people write them by hand.
    nodes.bullet_list = (state, node) => {
      state.renderList(node, "  ", () => "- ");
    };
    if (lines) {
      // The server turns each soft break of a line field into a break: a bare newline is enough.
      nodes.hard_break = (state, node, parent, index) => {
        for (let i = index + 1; i < parent.childCount; i++) {
          if (parent.child(i).type !== node.type) {
            state.write("\n");
            return;
          }
        }
      };
    }
    serializer = new MarkdownSerializer(
      nodes,
      {
        ...defaultMarkdownSerializer.marks,
        strike: {
          open: "~~",
          close: "~~",
          mixable: true,
          expelEnclosingWhitespace: true,
        },
      },
      { strict: false },
    );
    serializers.set(lines, serializer);
  }
  return serializer;
}

export function markdownToDoc(source: string, lines = false): PmNode {
  return parserFor(lines).parse(source);
}

export function docToMarkdown(doc: PmNode, lines = false): string {
  return serializerFor(lines).serialize(doc).replace(/\s+$/, "");
}

/** Block tokens the visual model cannot represent. */
const UNSUPPORTED = new Set(["table_open"]);

function comparable(html: string): string {
  return html.replace(/\s+/g, " ").trim();
}

/**
 * True when the visual editor can show and re-emit this text without changing how it renders.
 * Checked by parsing, serializing and comparing the rendered HTML of both texts.
 */
export function canEditVisually(source: string, lines = false): boolean {
  if (source.trim() === "") return true;
  try {
    // Full CommonMark plus tables so that a table is seen as one.
    const probe = MarkdownIt("commonmark", {
      html: false,
      breaks: lines,
    }).enable(["strikethrough", "table"]);
    if (probe.parse(source, {}).some((token) => UNSUPPORTED.has(token.type))) {
      return false;
    }
    const again = docToMarkdown(markdownToDoc(source, lines), lines);
    return comparable(probe.render(source)) === comparable(probe.render(again));
  } catch {
    return false;
  }
}
