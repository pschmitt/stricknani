// Build-only entry point consumed by esbuild (see package.json's "build"
// script / `just vendor-tiptap`). Re-exports exactly what
// stricknani/static/js/features/wysiwyg_editor.js imports, so the bundle is
// the one place that has to track TipTap's package layout.
export { Editor, mergeAttributes } from "@tiptap/core";
export { default as Image } from "@tiptap/extension-image";
export { default as Link } from "@tiptap/extension-link";
export { default as Underline } from "@tiptap/extension-underline";
export { Markdown } from "@tiptap/markdown";
export {
	chainCommands,
	createParagraphNear,
	liftEmptyBlock,
	newlineInCode,
	splitBlock,
} from "@tiptap/pm/commands";
export { default as StarterKit } from "@tiptap/starter-kit";
