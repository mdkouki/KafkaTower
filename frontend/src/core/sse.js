// Minimal Server-Sent Events reader for a fetch() Response body.
//
// Native EventSource can't be used here because it only supports GET requests,
// and this app's endpoints take a JSON POST body — so the stream has to be
// parsed by hand from response.body's ReadableStream instead.
//
// Per the SSE spec, one logical event can span several consecutive "data:"
// lines (e.g. Spring's SseEmitter splits any \n in a chunk this way); they
// must be rejoined with \n and dispatched together on the blank line that
// terminates the event, otherwise every embedded newline in a multi-line
// chunk is lost.
export async function readSseEvents(response, onEvent) {
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let eventDataLines = [];

  const flush = () => {
    if (eventDataLines.length === 0) return;
    onEvent(eventDataLines.join('\n'));
    eventDataLines = [];
  };

  const processLine = (line) => {
    if (line.startsWith('data:')) {
      eventDataLines.push(line.replace(/^data: ?/, ''));
    } else if (line === '') {
      flush();
    }
  };

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split('\n');
    buffer = lines.pop();
    lines.forEach(processLine);
  }

  // The stream may end without a trailing blank-line terminator — process
  // whatever's left in buffer through the same path, then flush it.
  processLine(buffer);
  flush();
}
