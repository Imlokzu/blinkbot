import React, { useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

function Fixture() {
  const [count, setCount] = useState(0);
  return <main><h1>React preview fixture</h1>
    <button id="react-counter" onClick={() => setCount(value => value + 1)}>{count}</button>
    <a id="react-route" href="./details">Details</a>
  </main>;
}

createRoot(document.getElementById('root')).render(<Fixture />);
