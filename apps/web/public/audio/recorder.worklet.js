class VeenaRecorder extends AudioWorkletProcessor {
	constructor() {
		super();
		this.buffer = new Float32Array(2048);
		this.offset = 0;
		this.port.onmessage = ({ data }) => {
			if (data === "flush") {
				if (this.offset)
					this.port.postMessage(this.buffer.slice(0, this.offset));
				this.offset = 0;
				this.port.postMessage({ flushed: true });
			}
		};
	}
	process(inputs) {
		const channels = inputs[0];
		if (!channels?.length) return true;
		for (let i = 0; i < channels[0].length; i++) {
			let value = 0;
			for (const channel of channels) value += channel[i];
			this.buffer[this.offset++] = value / channels.length;
			if (this.offset === this.buffer.length) {
				this.port.postMessage(this.buffer.slice());
				this.offset = 0;
			}
		}
		return true;
	}
}
registerProcessor("veena-recorder", VeenaRecorder);
