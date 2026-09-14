const VeenaTextLogo = ({
  width,
  height,
  className,
}: {
  width?: number;
  height?: number;
  className?: string;
}) => {
  return (
    <span
      className={className}
      style={{
        lineHeight: 1,
        fontWeight: "bold",
        fontSize: Math.min(width ?? 100, height ?? 50) * 0.6,
        color: "var(--color-logo-primary)",
      }}
    >
      veena
    </span>
  );
};

export default VeenaTextLogo;
