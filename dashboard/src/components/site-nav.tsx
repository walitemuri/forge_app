"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Boxes, LayoutDashboard, LayoutTemplate, Server } from "lucide-react";

const links = [
  { href: "/", label: "Overview", icon: LayoutDashboard },
  { href: "/workers", label: "Workers", icon: Server },
  { href: "/templates", label: "Templates", icon: LayoutTemplate },
];

export function SiteNav() {
  const pathname = usePathname();

  return (
    <header className="site-nav">
      <div className="site-nav__inner">
        <Link href="/" className="site-nav__brand" aria-label="Forge home">
          <span className="site-nav__mark"><Boxes size={20} /></span>
          Forge <span className="site-nav__edition">CONTROL PLANE</span>
        </Link>
        <nav aria-label="Main navigation">
          {links.map(({ href, label, icon: Icon }) => (
            <Link key={href} href={href} aria-current={pathname === href ? "page" : undefined}>
              <Icon size={15} aria-hidden="true" />{label}
            </Link>
          ))}
        </nav>
      </div>
    </header>
  );
}
