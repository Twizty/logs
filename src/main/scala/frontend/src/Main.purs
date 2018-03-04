module Main (main, foo) where

import Prelude
import Control.Monad.Eff (Eff)
import Control.Monad.Eff.Console (CONSOLE, log)

main :: forall e. Eff (console :: CONSOLE | e) Unit
main = do
  log "Hello sailor!"

foo :: forall a. Show a => Array a -> Array String
foo arr = show <$> arr