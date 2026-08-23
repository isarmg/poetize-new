import {createTimeline, utils} from 'animejs'

export default function () {
  // 动画效果
  var canvasEl = document.querySelector("#mousedown");
  if (canvasEl) {
    var ctx = canvasEl.getContext("2d", {willReadFrequently: true})
      , numberOfParticules = 30
      , pointerX = 0
      , pointerY = 0
      , tap = "mousedown"
      , colors = ["#FF1461", "#18FF92", "#5A87FF", "#FBF38C"]
      , setCanvasSize = debounce(function () {
      canvasEl.width = 2 * window.innerWidth,
        canvasEl.height = 2 * window.innerHeight,
        canvasEl.style.width = window.innerWidth + "px",
        canvasEl.style.height = window.innerHeight + "px",
        canvasEl.getContext("2d", {willReadFrequently: true}).scale(2, 2)
    }, 500);
    const onPointerDown = function (e) {
      if ("sidebar" !== e.target.id && "toggle-sidebar" !== e.target.id && "A" !== e.target.nodeName && "IMG" !== e.target.nodeName) {
        updateCoords(e);
        animateParticules(pointerX, pointerY);
      }
    };
    document.addEventListener(tap, onPointerDown, false);
    setCanvasSize();
    window.addEventListener("resize", setCanvasSize, false);

    return () => {
      document.removeEventListener(tap, onPointerDown, false);
      window.removeEventListener("resize", setCanvasSize, false);
      ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);
    };
  }

  function updateCoords(e) {
    pointerX = (e.clientX || e.touches[0].clientX) - canvasEl.getBoundingClientRect().left,
      pointerY = e.clientY || e.touches[0].clientY - canvasEl.getBoundingClientRect().top
  }

  function setParticuleDirection(e) {
    var t = utils.random(0, 360) * Math.PI / 180
      , a = utils.random(50, 180)
      , n = [-1, 1][utils.random(0, 1)] * a;
    return {
      x: e.x + n * Math.cos(t),
      y: e.y + n * Math.sin(t)
    }
  }

  function createParticule(e, t) {
    var a = {};
    return a.x = e,
      a.y = t,
      a.color = colors[utils.random(0, colors.length - 1)],
      a.radius = utils.random(16, 32),
      a.endPos = setParticuleDirection(a),
      a.draw = function () {
        ctx.beginPath(),
          ctx.arc(a.x, a.y, a.radius, 0, 2 * Math.PI, !0),
          ctx.fillStyle = a.color,
          ctx.fill()
      }
      ,
      a
  }

  function createCircle(e, t) {
    var a = {};
    return a.x = e,
      a.y = t,
      a.color = "#F00",
      a.radius = .1,
      a.alpha = .5,
      a.lineWidth = 6,
      a.draw = function () {
        ctx.globalAlpha = a.alpha,
          ctx.beginPath(),
          ctx.arc(a.x, a.y, a.radius, 0, 2 * Math.PI, !0),
          ctx.lineWidth = a.lineWidth,
          ctx.strokeStyle = a.color,
          ctx.stroke(),
          ctx.globalAlpha = 1
      }
      ,
      a
  }

  function animateParticules(e, t) {
    for (var a = createCircle(e, t), n = [], i = 0; i < numberOfParticules; i++)
      n.push(createParticule(e, t));
    createTimeline({
      onRender: function () {
        ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);
        n.forEach(particule => particule.draw());
        a.draw();
      },
      onComplete: function () {
        ctx.clearRect(0, 0, canvasEl.width, canvasEl.height);
      }
    }).add(n, {
      x: function (e) {
        return e.endPos.x
      },
      y: function (e) {
        return e.endPos.y
      },
      radius: .1,
      duration: utils.random(1200, 1800),
      ease: "outExpo"
    }, 0).add(a, {
      radius: utils.random(80, 160),
      lineWidth: 0,
      alpha: 0,
      duration: utils.random(1200, 1800),
      ease: "outExpo"
    }, 0)
  }

  function debounce(fn, delay) {
    var timer
    return function () {
      var context = this
      var args = arguments
      clearTimeout(timer)
      timer = setTimeout(function () {
        fn.apply(context, args)
      }, delay)
    }
  }
}
