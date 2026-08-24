import '../Style/document/VersionBadge.css';

interface VersionBadgeProps {
    label?: string | null;
}

/**
 * Affiche "Version N" ou "Final" — rien si le document n'a jamais été
 * versionné (label null/undefined), conformément à ce qui a été décidé :
 * pas de badge tant qu'aucune chaîne de versions n'existe réellement.
 */
function VersionBadge({ label }: VersionBadgeProps) {
    if (!label) return null;

    const isFinal = label === 'Final';

    return (
        <span className={`version-badge ${isFinal ? 'version-badge-final' : 'version-badge-old'}`}>
            {isFinal && <i className="fa-solid fa-check" />}
            {label}
        </span>
    );
}

export default VersionBadge;
