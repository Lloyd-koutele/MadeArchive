function Pagination({ currentPage, totalPages, onChange }) {
    if (totalPages <= 1) return null;

    return (
        <div className="pagination">
            <button className="pagination-btn pagination-nav" onClick={() => onChange(1)} disabled={currentPage === 1}>«</button>
            <button className="pagination-btn pagination-nav" onClick={() => onChange(currentPage - 1)} disabled={currentPage === 1}>‹</button>

            {Array.from({ length: totalPages }, (_, i) => i + 1).map(page => (
                <button
                    key={page}
                    onClick={() => onChange(page)}
                    className={`pagination-btn${page === currentPage ? ' pagination-active' : ''}`}
                >
                    {page}
                </button>
            ))}

            <button className="pagination-btn pagination-nav" onClick={() => onChange(currentPage + 1)} disabled={currentPage === totalPages}>›</button>
            <button className="pagination-btn pagination-nav" onClick={() => onChange(totalPages)} disabled={currentPage === totalPages}>»</button>
        </div>
    );
}

export default Pagination;