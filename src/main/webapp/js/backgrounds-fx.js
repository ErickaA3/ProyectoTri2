/* ============================================================
   backgrounds-fx.js — Mi ProfesorIA
   Animaciones de canvas compartidas entre tienda y chat.
   Auto-detecta el contenedor: .chat-main (chat) o .content (tienda).
   La clase bg-* siempre se lee desde .content.
   ============================================================ */

(function () {
    // Contenedor donde viven los canvas (para sizing)
    const container = document.querySelector('.chat-main') || document.querySelector('.content');
    // Elemento donde se aplica bg-* (para saber cuál fondo está activo)
    const classEl = document.querySelector('.content');
    if (!container || !classEl) return;

    function initCanvas(id) {
        const c = document.getElementById(id);
        if (!c) return null;
        const ctx = c.getContext('2d');
        function resize() { c.width = container.offsetWidth; c.height = container.offsetHeight; }
        resize();
        window.addEventListener('resize', resize);
        return { c, ctx, resize };
    }

    function hasBg(name) { return classEl.classList.contains(name); }

    // ── GALAXIA ──────────────────────────────────────────────
    (function () {
        const o = initCanvas('galaxyCanvas'); if (!o) return;
        const { c, ctx } = o;
        const stars = [];
        for (let i = 0; i < 200; i++) {
            stars.push({
                x: Math.random()*c.width, y: Math.random()*c.height,
                size: Math.random()*2+0.3, speedX: (Math.random()-0.5)*0.15,
                speedY: (Math.random()-0.5)*0.15, opacity: Math.random()*0.4+0.1,
                opacityChange: (Math.random()-0.5)*0.015,
                color: ['#fff','#fff','#ffe9c4','#d4f1ff','#ffccaa','#aaddff'][Math.floor(Math.random()*6)]
            });
        }
        (function animate() {
            if (!hasBg('bg-galaxy')) { requestAnimationFrame(animate); return; }
            ctx.clearRect(0,0,c.width,c.height);
            stars.forEach(s => {
                s.x += s.speedX; s.y += s.speedY;
                if (s.x<0) s.x=c.width; if (s.x>c.width) s.x=0;
                if (s.y<0) s.y=c.height; if (s.y>c.height) s.y=0;
                s.opacity += s.opacityChange;
                if (s.opacity<=0.2||s.opacity>=1) s.opacityChange*=-1;
                ctx.beginPath(); ctx.arc(s.x,s.y,s.size,0,Math.PI*2);
                ctx.fillStyle=s.color; ctx.globalAlpha=s.opacity; ctx.fill();
                if (s.size>1.5) {
                    ctx.beginPath(); ctx.arc(s.x,s.y,s.size*2,0,Math.PI*2);
                    ctx.fillStyle=s.color; ctx.globalAlpha=s.opacity*0.3; ctx.fill();
                }
            });
            ctx.globalAlpha=1; requestAnimationFrame(animate);
        })();
    })();

    // ── FOREST ───────────────────────────────────────────────
    (function () {
        const o = initCanvas('forestCanvas'); if (!o) return;
        const { c, ctx } = o;
        const leafImg = new Image(); leafImg.src = '../images/backgrounds/leaf.png';
        const leaves = [];
        function createLeaf() {
            return { x: Math.random()*c.width, y: -100, scale: Math.random()*0.15+0.08,
                speedY: Math.random()*0.6+0.3, speedX: (Math.random()-0.5)*0.5,
                rotation: Math.random()*Math.PI*2, rotationSpeed: (Math.random()-0.5)*0.02,
                opacity: Math.random()*0.5+0.5, wobble: Math.random()*Math.PI*2, wobbleSpeed: Math.random()*0.03+0.01 };
        }
        for (let i=0;i<15;i++) { const l=createLeaf(); l.y=Math.random()*c.height; leaves.push(l); }
        let forestRunning = false;
        function animate() {
            if (forestRunning) return;
            forestRunning = true;
            (function loop() {
                if (!hasBg('bg-forest')) { requestAnimationFrame(loop); return; }
                ctx.clearRect(0,0,c.width,c.height);
                if (leafImg.complete && leafImg.naturalWidth>0) {
                    leaves.forEach((l,i) => {
                        l.wobble+=l.wobbleSpeed; l.y+=l.speedY; l.x+=l.speedX+Math.sin(l.wobble)*0.2; l.rotation+=l.rotationSpeed;
                        if (l.y>c.height+100) leaves[i]=createLeaf();
                        if (l.x<-100) l.x=c.width+50; if (l.x>c.width+100) l.x=-50;
                        ctx.save(); ctx.translate(l.x,l.y); ctx.rotate(l.rotation); ctx.globalAlpha=l.opacity;
                        const w=leafImg.width*l.scale, h=leafImg.height*l.scale;
                        ctx.drawImage(leafImg,-w/2,-h/2,w,h); ctx.restore();
                    });
                }
                ctx.globalAlpha=1; requestAnimationFrame(loop);
            })();
        }
        leafImg.onload = animate;
        if (leafImg.complete) animate();
    })();

    // ── VOLCÁN ───────────────────────────────────────────────
    (function () {
        const o = initCanvas('volcanoCanvas'); if (!o) return;
        const { c, ctx } = o;
        let time = 0;
        const ashes = [];
        for (let i=0;i<60;i++) ashes.push(mkAsh());
        function mkAsh() {
            return { x:Math.random()*c.width, y:c.height+Math.random()*50,
                size:Math.random()*3+1, speedY:-(Math.random()*0.8+0.3),
                speedX:(Math.random()-0.3)*0.5, opacity:Math.random()*0.4+0.2,
                wobble:Math.random()*Math.PI*2, wobbleSpeed:Math.random()*0.02+0.01 };
        }
        const sparks = [];
        function mkSpark() {
            return { x:c.width*0.3+Math.random()*c.width*0.4, y:c.height*0.7+Math.random()*c.height*0.2,
                size:Math.random()*4+2, life:1, decay:Math.random()*0.03+0.02,
                speedY:-(Math.random()*2+1), speedX:(Math.random()-0.5)*2 };
        }
        function animate() {
            if (!hasBg('bg-volcano')) { requestAnimationFrame(animate); return; }
            ctx.clearRect(0,0,c.width,c.height); time+=0.02;
            // bottom light
            const bi=0.1+Math.sin(time*0.3)*0.05;
            const bg=ctx.createLinearGradient(0,c.height,0,c.height*0.6);
            bg.addColorStop(0,`rgba(255,80,20,${bi})`); bg.addColorStop(0.5,`rgba(255,50,0,${bi*0.3})`); bg.addColorStop(1,'transparent');
            ctx.fillStyle=bg; ctx.fillRect(0,c.height*0.5,c.width,c.height*0.5);
            // heat waves
            const wo=0.03+Math.sin(time*2)*0.02;
            for(let i=0;i<3;i++) {
                ctx.save(); ctx.globalAlpha=wo; ctx.strokeStyle='rgba(255,150,50,0.3)';
                ctx.lineWidth=30+i*20; ctx.lineCap='round'; ctx.beginPath();
                ctx.moveTo(0,c.height*(0.5+i*0.1));
                for(let x=0;x<=c.width;x+=10) ctx.lineTo(x,c.height*(0.5+i*0.1)+Math.sin(x*0.01+time+i)*(20+i*10));
                ctx.stroke(); ctx.restore();
            }
            // lava glow
            const b=Math.sin(time*0.8)*0.08+0.15;
            [[0.5,1,0.6],[0.2,0.9,0.4],[0.8,0.85,0.35]].forEach(([cx,cy,r],idx) => {
                const g=ctx.createRadialGradient(c.width*cx,c.height*cy,0,c.width*cx,c.height*cy,r*c.height);
                g.addColorStop(0,`rgba(255,${100-idx*10},0,${b})`); g.addColorStop(0.5,`rgba(255,${60-idx*10},0,${b*0.3})`); g.addColorStop(1,'transparent');
                ctx.fillStyle=g; ctx.fillRect(0,0,c.width,c.height);
            });
            // ashes
            ashes.forEach((a,i) => {
                a.wobble+=a.wobbleSpeed; a.y+=a.speedY; a.x+=a.speedX+Math.sin(a.wobble)*0.15;
                if(a.y<-20) ashes[i]=mkAsh();
                ctx.save(); ctx.globalAlpha=a.opacity; ctx.fillStyle='#3a3a3a';
                ctx.beginPath(); ctx.arc(a.x,a.y,a.size,0,Math.PI*2); ctx.fill(); ctx.restore();
            });
            // sparks
            if(Math.random()<0.03) sparks.push(mkSpark());
            for(let i=sparks.length-1;i>=0;i--) {
                const s=sparks[i]; s.life-=s.decay; s.y+=s.speedY; s.x+=s.speedX; s.speedY+=0.05;
                if(s.life<=0) { sparks.splice(i,1); continue; }
                ctx.save(); ctx.globalAlpha=s.life;
                const g=ctx.createRadialGradient(s.x,s.y,0,s.x,s.y,s.size*3);
                g.addColorStop(0,'#ffff00'); g.addColorStop(0.3,'#ff8800'); g.addColorStop(1,'transparent');
                ctx.fillStyle=g; ctx.beginPath(); ctx.arc(s.x,s.y,s.size*3,0,Math.PI*2); ctx.fill();
                ctx.fillStyle='#fff'; ctx.beginPath(); ctx.arc(s.x,s.y,s.size*0.5,0,Math.PI*2); ctx.fill();
                ctx.restore();
            }
            requestAnimationFrame(animate);
        }
        animate();
    })();

    // ── OCÉANO ───────────────────────────────────────────────
    (function () {
        const o = initCanvas('oceanCanvas'); if (!o) return;
        const { c, ctx } = o;
        const bubbles = [];
        function mkB() {
            return { x:Math.random()*c.width, y:c.height+Math.random()*100,
                size:Math.random()*8+2, speedY:-(Math.random()*1.2+0.6), speedX:(Math.random()-0.5)*0.4,
                wobble:Math.random()*Math.PI*2, wobbleSpeed:Math.random()*0.03+0.015, opacity:Math.random()*0.5+0.2 };
        }
        for(let i=0;i<50;i++) bubbles.push(mkB());
        function animate() {
            if (!hasBg('bg-ocean')) { requestAnimationFrame(animate); return; }
            ctx.clearRect(0,0,c.width,c.height);
            bubbles.forEach((b,i) => {
                b.y+=b.speedY; b.x+=b.speedX; b.wobble+=b.wobbleSpeed;
                if(b.y<-20) bubbles[i]=mkB();
                const wx=Math.sin(b.wobble)*2;
                ctx.save(); ctx.globalAlpha=b.opacity;
                ctx.beginPath(); ctx.arc(b.x+wx,b.y,b.size,0,Math.PI*2);
                ctx.strokeStyle='rgba(150,220,255,0.6)'; ctx.lineWidth=1; ctx.stroke();
                ctx.beginPath(); ctx.arc(b.x+wx-b.size*.3,b.y-b.size*.3,b.size*.3,0,Math.PI*2);
                ctx.fillStyle='rgba(200,240,255,0.5)'; ctx.fill(); ctx.restore();
            });
            ctx.globalAlpha=1; requestAnimationFrame(animate);
        }
        animate();
    })();

    // ── SKY ──────────────────────────────────────────────────
    (function () {
        const o = initCanvas('skyCanvas'); if (!o) return;
        const { c, ctx } = o;
        const cloudImg = new Image(); cloudImg.src = '../images/backgrounds/cloud.png';
        const stars = [];
        for(let i=0;i<100;i++) stars.push({ x:Math.random()*c.width, y:Math.random()*c.height*0.7,
            size:Math.random()*1.5+0.3, opacity:Math.random()*0.6+0.2, opacityChange:(Math.random()-0.5)*0.01 });
        const clouds = [];
        function mkC(idx) {
            return { x:idx!==undefined?(c.width/8)*idx-200:-300,
                y:Math.random()*c.height*0.7, scale:Math.random()*0.4+0.3,
                speed:Math.random()*0.4+0.2, opacity:Math.random()*0.4+0.3 };
        }
        for(let i=0;i<8;i++) clouds.push(mkC(i));
        let skyRunning = false;
        function animate() {
            if (skyRunning) return;
            skyRunning = true;
            (function loop() {
                if (!hasBg('bg-sky')) { requestAnimationFrame(loop); return; }
                ctx.clearRect(0,0,c.width,c.height);
                stars.forEach(s => {
                    s.opacity+=s.opacityChange;
                    if(s.opacity<0.1||s.opacity>0.8) s.opacityChange*=-1;
                    ctx.beginPath(); ctx.arc(s.x,s.y,s.size,0,Math.PI*2);
                    ctx.fillStyle=`rgba(255,255,255,${s.opacity})`; ctx.fill();
                });
                if(cloudImg.complete&&cloudImg.naturalWidth) {
                    clouds.forEach((cl,i) => {
                        cl.x+=cl.speed;
                        if(cl.x>c.width+100) { clouds[i]=mkC(); clouds[i].x=-cloudImg.width*clouds[i].scale; }
                        ctx.save(); ctx.globalAlpha=cl.opacity;
                        ctx.drawImage(cloudImg,cl.x,cl.y,cloudImg.width*cl.scale,cloudImg.height*cl.scale);
                        ctx.restore();
                    });
                }
                requestAnimationFrame(loop);
            })();
        }
        cloudImg.onload = animate;
        if(cloudImg.complete) animate();
    })();

    // ── RAIN ─────────────────────────────────────────────────
    (function () {
        const o = initCanvas('rainCanvas'); if (!o) return;
        const { c, ctx } = o;
        const drops = [];
        function mkD() {
            const t=Math.random()<0.2;
            return { x:Math.random()*c.width, y:Math.random()*c.height-c.height,
                length:Math.random()*150+80, speed:Math.random()*3+1.5,
                opacity:Math.random()*0.6+0.4, width:Math.random()*2+1,
                color:t?'#2dd4bf':'#ffffff' };
        }
        for(let i=0;i<100;i++) drops.push(mkD());
        function animate() {
            if (!hasBg('bg-rain')) { requestAnimationFrame(animate); return; }
            ctx.clearRect(0,0,c.width,c.height);
            drops.forEach((d,i) => {
                d.y+=d.speed;
                if(d.y>c.height+d.length) { drops[i]=mkD(); drops[i].y=-drops[i].length; }
                ctx.save();
                const g=ctx.createLinearGradient(d.x,d.y,d.x,d.y+d.length);
                if(d.color==='#2dd4bf') {
                    g.addColorStop(0,`rgba(45,212,191,${d.opacity})`); g.addColorStop(0.4,`rgba(45,212,191,${d.opacity*0.6})`);
                } else {
                    g.addColorStop(0,`rgba(255,255,255,${d.opacity})`); g.addColorStop(0.4,`rgba(255,255,255,${d.opacity*0.5})`);
                }
                g.addColorStop(1,'transparent');
                ctx.strokeStyle=g; ctx.lineWidth=d.width; ctx.lineCap='round';
                ctx.beginPath(); ctx.moveTo(d.x,d.y); ctx.lineTo(d.x,d.y+d.length); ctx.stroke();
                ctx.beginPath(); ctx.arc(d.x,d.y,d.color==='#2dd4bf'?4:2,0,Math.PI*2);
                ctx.fillStyle=d.color==='#2dd4bf'?`rgba(45,212,191,${d.opacity})`:`rgba(255,255,255,${d.opacity*0.8})`;
                ctx.fill();
                if(d.color==='#2dd4bf') {
                    ctx.beginPath(); ctx.arc(d.x,d.y,8,0,Math.PI*2);
                    ctx.fillStyle=`rgba(45,212,191,${d.opacity*0.3})`; ctx.fill();
                }
                ctx.restore();
            });
            ctx.globalAlpha=1; requestAnimationFrame(animate);
        }
        animate();
    })();

    // ── AURORA ────────────────────────────────────────────────
    (function () {
        const o = initCanvas('auroraCanvas'); if (!o) return;
        const { c, ctx } = o;
        let time = 0;
        const curtains = [];
        for(let i=0;i<8;i++) curtains.push({ x:(c.width/8)*i+Math.random()*50, width:Math.random()*80+40,
            speed:Math.random()*0.3+0.1, opacity:Math.random()*0.1+0.15, phase:Math.random()*Math.PI*2 });
        function animate() {
            if (!hasBg('bg-aurora')) { requestAnimationFrame(animate); return; }
            ctx.clearRect(0,0,c.width,c.height); time+=0.02;
            // glow
            ctx.save();
            const br=Math.sin(time*0.3)*0.03+0.12;
            [[0.5,0.2,0.6],[0,0.4,0.4]].forEach(([cx,cy,r]) => {
                const g=ctx.createRadialGradient(c.width*cx,c.height*cy,0,c.width*cx,c.height*cy,c.width*r);
                g.addColorStop(0,`rgba(80,255,120,${br})`); g.addColorStop(0.5,`rgba(50,200,100,${br*0.5})`); g.addColorStop(1,'transparent');
                ctx.fillStyle=g; ctx.fillRect(0,0,c.width,c.height);
            });
            ctx.restore();
            // magnetic flow
            ctx.save(); ctx.globalAlpha=0.15;
            for(let w=0;w<5;w++) {
                ctx.beginPath();
                const baseY=c.height*0.3+w*60, amp=40+w*10, freq=0.003+w*0.001;
                ctx.moveTo(0,baseY);
                for(let x=0;x<=c.width;x+=5) ctx.lineTo(x,baseY+Math.sin(x*freq+time*0.5+w*0.5)*amp+Math.sin(x*freq*2+time*0.3)*(amp*0.3));
                const wg=ctx.createLinearGradient(0,baseY-amp,0,baseY+amp);
                wg.addColorStop(0,'transparent'); wg.addColorStop(0.5,'rgba(50,255,100,0.6)'); wg.addColorStop(1,'transparent');
                ctx.strokeStyle=wg; ctx.lineWidth=20+w*5; ctx.lineCap='round'; ctx.filter='blur(8px)'; ctx.stroke();
            }
            ctx.filter='none'; ctx.restore();
            requestAnimationFrame(animate);
        }
        animate();
    })();
})();