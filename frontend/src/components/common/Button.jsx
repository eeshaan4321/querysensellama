import './Button.css';

export function Button({ children, variant = 'primary', loading, ...props }) {
  return (
    <button className={`qs-button qs-button--${variant}`} disabled={loading || props.disabled} {...props}>
      {loading ? <span className="qs-button__spinner" aria-hidden="true" /> : null}
      <span className={loading ? 'qs-button__label qs-button__label--loading' : 'qs-button__label'}>
        {children}
      </span>
    </button>
  );
}
