// Flat ESLint config for Angular 21+ with @angular-eslint v21.
const eslint = require("@eslint/js");
const tsParser = require("@typescript-eslint/parser");
const tsPlugin = require("@typescript-eslint/eslint-plugin");
const angular = require("@angular-eslint/eslint-plugin");
const angularTemplate = require("@angular-eslint/eslint-plugin-template");
const angularTemplateParser = require("@angular-eslint/template-parser");

// Browser + ES2024 globals (no `globals` package needed for the small set we care about).
const browserAndEsGlobals = {
	console: "readonly",
	window: "readonly",
	document: "readonly",
	navigator: "readonly",
	location: "readonly",
	fetch: "readonly",
	setTimeout: "readonly",
	clearTimeout: "readonly",
	setInterval: "readonly",
	clearInterval: "readonly",
	queueMicrotask: "readonly",
	requestAnimationFrame: "readonly",
	cancelAnimationFrame: "readonly",
	URL: "readonly",
	URLSearchParams: "readonly",
	HTMLElement: "readonly",
	HTMLDivElement: "readonly",
	HTMLInputElement: "readonly",
	Event: "readonly",
	CustomEvent: "readonly",
	KeyboardEvent: "readonly",
	MouseEvent: "readonly",
	structuredClone: "readonly",
};

module.exports = [
	{
		files: ["**/*.ts"],
		languageOptions: {
			parser: tsParser,
			parserOptions: {project: ["./tsconfig.app.json"]},
			globals: browserAndEsGlobals,
		},
		plugins: {
			"@typescript-eslint": tsPlugin,
			"@angular-eslint": angular,
		},
		rules: {
			...eslint.configs.recommended.rules,
			...tsPlugin.configs.recommended.rules,
			...angular.configs.recommended.rules,

			// Project conventions
			"@angular-eslint/component-selector": [
				"error",
				{type: "element", prefix: "app", style: "kebab-case"},
			],
			"@angular-eslint/directive-selector": [
				"error",
				{type: "attribute", prefix: "app", style: "camelCase"},
			],
			"@angular-eslint/prefer-standalone": "error",
			"@typescript-eslint/no-unused-vars": ["warn", {argsIgnorePattern: "^_"}],
			"@typescript-eslint/explicit-member-accessibility": [
				"error",
				{accessibility: "no-public"},
			],
		},
	},
	{
		files: ["**/*.html"],
		languageOptions: {parser: angularTemplateParser},
		plugins: {"@angular-eslint/template": angularTemplate},
		rules: {
			...angularTemplate.configs.recommended.rules,
			...angularTemplate.configs.accessibility.rules,
		},
	},
];
