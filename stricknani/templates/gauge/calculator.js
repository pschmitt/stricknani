    document.addEventListener('DOMContentLoaded', () => {
        const patternStitches = document.getElementById('pattern_gauge_stitches');
        const patternRows = document.getElementById('pattern_gauge_rows');
        const userStitches = document.getElementById('user_gauge_stitches');
        const userRows = document.getElementById('user_gauge_rows');

        const mirrorIfEmpty = (source, target) => {
            if (!target || !source) {
                return;
            }
            const wasAutofilled = target.dataset.autofilled === 'true';
            if (!target.value || wasAutofilled) {
                target.value = source.value;
                if (source.value) {
                    target.dataset.autofilled = 'true';
                } else {
                    delete target.dataset.autofilled;
                }
            }
        };

        const stopAutofill = (target) => {
            if (target) {
                delete target.dataset.autofilled;
            }
        };

        patternStitches?.addEventListener('input', () => mirrorIfEmpty(patternStitches, userStitches));
        patternRows?.addEventListener('input', () => mirrorIfEmpty(patternRows, userRows));
        userStitches?.addEventListener('input', () => stopAutofill(userStitches));
        userRows?.addEventListener('input', () => stopAutofill(userRows));
    });

    document.body.addEventListener('htmx:afterRequest', function (event) {
        if (event.detail.target.id === 'result') {
            try {
                const data = JSON.parse(event.detail.xhr.responseText);
                const adjustedRows = data.adjusted_rows === null ? '{{ _('N/A') }}' : data.adjusted_rows;
    const patternRows = data.pattern_row_count === null ? '' : ` {{ _('and') }} ${data.pattern_row_count} {{ _('rows') }}`;
    const resultHtml = `
                <div class="alert alert-success">
                    <span class="mdi mdi-check-decagram text-2xl"></span>
                    <div class="w-full">
                        <h3 class="font-bold mb-4">{{ _('Calculation Results') }}</h3>
                        <div class="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <div class="stats shadow">
                                <div class="stat">
                                    <div class="stat-title">{{ _('Adjusted Stitches') }}</div>
                                    <div class="stat-value text-primary">${data.adjusted_stitches}</div>
                                </div>
                            </div>
                            <div class="stats shadow">
                                <div class="stat">
                                    <div class="stat-title">{{ _('Adjusted Rows') }}</div>
                                    <div class="stat-value text-primary">${adjustedRows}</div>
                                </div>
                            </div>
                        </div>
                        <div class="mt-4 text-sm opacity-70">
                            <p>{{ _('Based on a pattern cast-on of') }} ${data.pattern_cast_on_stitches} {{ _('stitches') }}${patternRows}.</p>
                        </div>
                    </div>
                </div>
            `;
    event.detail.target.innerHTML = resultHtml;
        } catch (e) {
        console.error('Error parsing gauge calculation result:', e);
    }
    }
});
