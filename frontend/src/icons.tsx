import type { ReactNode } from 'react';

/** A handful of stroke icons, drawn as React elements. Decorative: the text next to them carries the meaning. */

type IconProps = { size?: number; className?: string };

function Svg({ size = 18, className, children }: IconProps & { children: ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.8}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={className}
    >
      {children}
    </svg>
  );
}

export const ChevronLeft = (p: IconProps) => <Svg {...p}><path d="M15 6l-6 6 6 6" /></Svg>;
export const ChevronRight = (p: IconProps) => <Svg {...p}><path d="M9 6l6 6-6 6" /></Svg>;
export const ChevronDown = (p: IconProps) => <Svg {...p}><path d="M6 9l6 6 6-6" /></Svg>;
export const Sun = (p: IconProps) => (
  <Svg {...p}>
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </Svg>
);
export const Moon = (p: IconProps) => <Svg {...p}><path d="M20 14.5A8 8 0 019.5 4a8 8 0 1010.5 10.5z" /></Svg>;
export const SignOut = (p: IconProps) => (
  <Svg {...p}><path d="M15 4h3a2 2 0 012 2v12a2 2 0 01-2 2h-3M10 17l-5-5 5-5M5 12h11" /></Svg>
);
export const Copy = (p: IconProps) => (
  <Svg {...p}><rect x="9" y="9" width="11" height="11" rx="2" /><path d="M5 15V6a2 2 0 012-2h9" /></Svg>
);
export const Check = (p: IconProps) => <Svg {...p}><path d="M5 12.5l4.5 4.5L19 7.5" /></Svg>;
export const Close = (p: IconProps) => <Svg {...p}><path d="M6 6l12 12M18 6L6 18" /></Svg>;
export const Warning = (p: IconProps) => (
  <Svg {...p}><path d="M12 3.5l9.5 16.5h-19z" /><path d="M12 10v4.5M12 17.5v.01" /></Svg>
);
export const Info = (p: IconProps) => (
  <Svg {...p}><circle cx="12" cy="12" r="9" /><path d="M12 11v5.5M12 7.5v.01" /></Svg>
);
export const Refresh = (p: IconProps) => (
  <Svg {...p}><path d="M20 12a8 8 0 11-2.3-5.7M20 4v5h-5" /></Svg>
);
export const Plus = (p: IconProps) => <Svg {...p}><path d="M12 5v14M5 12h14" /></Svg>;
export const Trash = (p: IconProps) => (
  <Svg {...p}><path d="M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3" /></Svg>
);
export const Pencil = (p: IconProps) => <Svg {...p}><path d="M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4" /></Svg>;
export const Undo = (p: IconProps) => <Svg {...p}><path d="M9 14L4 9l5-5M4 9h10a6 6 0 010 12h-3" /></Svg>;
export const Search = (p: IconProps) => <Svg {...p}><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></Svg>;
export const Phone = (p: IconProps) => (
  <Svg {...p}><rect x="7" y="2.5" width="10" height="19" rx="2" /><path d="M11 18.5h2" /></Svg>
);
export const Desktop = (p: IconProps) => (
  <Svg {...p}><rect x="3" y="4" width="18" height="12" rx="1.5" /><path d="M9 20h6M12 16v4" /></Svg>
);
