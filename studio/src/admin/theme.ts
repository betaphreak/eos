// CivStudio brand theme for the Strapi admin — a faithful port of the web/ viewer's design tokens
// (web/styles.css :root). Cool bluish-grey neutrals, a gold/amber primary, a deep-navy dark mode,
// and status colours matched to web/. Strapi merges this over its default DS theme, so we only set
// the ramp keys we retint. Neutral direction (both modes): neutral0 = base surface, neutral1000 =
// strongest text.

// web/ accents: light --accent #a9690f (brown-gold), dark --accent #e6b04a (bright gold).
// In Strapi, `primary600` is the main action fill; 100/200 are subtle backgrounds (selected rows),
// 500 focus/border, 700 hover/pressed + link text (lighter in dark for contrast).
const light = {
  colors: {
    // neutrals — web/ light: --panel-2 #fff, --panel #f3f6fa, --line #d3dae4, --ink #18202e …
    neutral0: '#ffffff',
    neutral100: '#f3f6fa',
    neutral150: '#eef2f7',
    neutral200: '#e2e7ee',
    neutral300: '#d3dae4',
    neutral400: '#b7c0ce',
    neutral500: '#8b95a8',
    neutral600: '#56617a',
    neutral700: '#3a4356',
    neutral800: '#18202e',
    neutral900: '#131a26',
    neutral1000: '#0b0f18',
    // primary — gold
    primary100: '#fbf2e2',
    primary200: '#f3e1c0',
    primary500: '#cf8f1e',
    primary600: '#a9690f',
    primary700: '#7c4c0a',
    buttonPrimary500: '#cf8f1e',
    buttonPrimary600: '#a9690f',
    // secondary — web/ blue (#6ea8ff / #4f8fce)
    secondary100: '#e7f0fb',
    secondary200: '#c2dcf6',
    secondary500: '#5f97e0',
    secondary600: '#4f8fce',
    secondary700: '#356aa0',
    // danger #e5484d
    danger100: '#fcecec',
    danger200: '#f5c0c0',
    danger500: '#ee5c5c',
    danger600: '#e5484d',
    danger700: '#b8272c',
    // success (web/ --good light #3f8f5a)
    success100: '#e9f6ee',
    success200: '#bfe4cd',
    success500: '#55b177',
    success600: '#3f8f5a',
    success700: '#2f6f45',
    // warning — amber (web/ warn), kept a touch brighter than the brown-gold primary
    warning100: '#fcf3e0',
    warning200: '#f5dfae',
    warning500: '#e0a83a',
    warning600: '#c98f1e',
    warning700: '#9a6c14',
  },
};

const dark = {
  colors: {
    // neutrals — web/ dark: --ground #0d1119, --panel #141a26, --panel-2 #1a2130, --line #232c3c, --ink #e7ecf4 …
    neutral0: '#0d1119',
    neutral100: '#141a26',
    neutral150: '#171e2b',
    neutral200: '#1a2130',
    neutral300: '#232c3c',
    neutral400: '#313c50',
    neutral500: '#6b7688',
    neutral600: '#9aa6ba',
    neutral700: '#b8c2d2',
    neutral800: '#e7ecf4',
    neutral900: '#f0f3f8',
    neutral1000: '#ffffff',
    // primary — bright gold (brightened for dark, as web/ --accent does)
    primary100: '#2a2213',
    primary200: '#3d3016',
    primary500: '#c79433',
    primary600: '#e6b04a',
    primary700: '#f0c877',
    buttonPrimary500: '#c79433',
    buttonPrimary600: '#e6b04a',
    // secondary — blue
    secondary100: '#12233b',
    secondary200: '#1b3352',
    secondary500: '#5f97e0',
    secondary600: '#6ea8ff',
    secondary700: '#a9ccff',
    // danger
    danger100: '#2b1618',
    danger200: '#3d1c1e',
    danger500: '#e5646a',
    danger600: '#e5484d',
    danger700: '#f08b8f',
    // success (web/ --good dark #5ab27a)
    success100: '#15251b',
    success200: '#1d3527',
    success500: '#46a069',
    success600: '#5ab27a',
    success700: '#86d79f',
    // warning — amber
    warning100: '#2a2213',
    warning200: '#3d3016',
    warning500: '#e0a83a',
    warning600: '#e6b04a',
    warning700: '#f0c877',
  },
};

export const civstudioTheme = { light, dark };
